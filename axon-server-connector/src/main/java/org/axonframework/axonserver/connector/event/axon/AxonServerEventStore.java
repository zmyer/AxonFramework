/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.axonserver.connector.event.axon;

import com.google.protobuf.ByteString;
import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.EventWithToken;
import io.axoniq.axonserver.grpc.event.GetAggregateEventsRequest;
import io.axoniq.axonserver.grpc.event.GetEventsRequest;
import io.axoniq.axonserver.grpc.event.QueryEventsRequest;
import io.axoniq.axonserver.grpc.event.QueryEventsResponse;
import io.axoniq.axonserver.grpc.event.ReadHighestSequenceNrResponse;
import io.grpc.stub.StreamObserver;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.ErrorCode;
import org.axonframework.axonserver.connector.PlatformConnectionManager;
import org.axonframework.axonserver.connector.event.AppendEventTransaction;
import org.axonframework.axonserver.connector.event.AxonDBClient;
import org.axonframework.axonserver.connector.util.FlowControllingStreamObserver;
import org.axonframework.axonserver.connector.util.GrpcMetaDataConverter;
import org.axonframework.common.Assert;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventsourcing.eventstore.AbstractEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.AbstractEventStore;
import org.axonframework.eventsourcing.eventstore.DomainEventData;
import org.axonframework.eventsourcing.eventstore.DomainEventStream;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.eventsourcing.eventstore.EventUtils;
import org.axonframework.eventsourcing.eventstore.GlobalSequenceTrackingToken;
import org.axonframework.eventsourcing.eventstore.TrackedEventData;
import org.axonframework.eventsourcing.eventstore.TrackingEventStream;
import org.axonframework.eventsourcing.eventstore.TrackingToken;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.serialization.SerializedObject;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.serialization.upcasting.event.NoOpEventUpcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Axon EventStore implementation that connects to the AxonIQ AxonServer Server to store and retrieve Events.
 *
 * @author Zoltan Altfatter
 * @author Marc Gathier
 * @author Allard Buijze
 */
public class AxonServerEventStore extends AbstractEventStore {

    private static final Logger logger = LoggerFactory.getLogger(AxonServerEventStore.class);

    /**
     * Initialize the Event Store using given {@code configuration} and given {@link Serializer}. <b>Note</b> that the
     * Serializer is used for serializing both Events and Snapshots.
     * <p>
     * The Event Store will delay creating the connection until the first activity takes place.
     *
     * @param configuration             The configuration describing the servers to connect with and how to manage flow
     *                                  control
     * @param platformConnectionManager manager for connections to AxonServer platform
     * @param serializer                a {@link Serializer} used to de/serialize Event payloads, Meta Data ans
     *                                  Snapshots with
     */
    public AxonServerEventStore(AxonServerConfiguration configuration,
                                PlatformConnectionManager platformConnectionManager,
                                Serializer serializer) {
        this(configuration, platformConnectionManager, serializer, NoOpEventUpcaster.INSTANCE);
    }

    /**
     * Initialize the Event Store using given {@code configuration}, {@link Serializer} and {@link EventUpcaster}.
     * <b>Note</b> that the Serializer is used for serializing both Events and Snapshots.
     * <p>
     * The Event Store will delay creating the connection until the first activity takes place.
     *
     * @param configuration             The configuration describing the servers to connect with and how to manage flow
     *                                  control
     * @param platformConnectionManager manager for connections to AxonServer platform
     * @param serializer                a {@link Serializer} used to de/serialize Event payloads, Meta Data ans
     *                                  Snapshots with
     * @param upcasterChain             The upcaster to modify received Event representations with
     */
    public AxonServerEventStore(AxonServerConfiguration configuration,
                                PlatformConnectionManager platformConnectionManager,
                                Serializer serializer,
                                EventUpcaster upcasterChain) {
        this(configuration, platformConnectionManager, serializer, serializer, upcasterChain);
    }

    /**
     * Initialize the Event Store using the given {@code configuration}, snapshot {@link Serializer}, event Serializer
     * and {@code upcasterChain}. Allows for different serializers for snapshots and events
     * (required for AxonFramework 3.3 or higher).
     *
     * @param configuration             The configuration describing the servers to connect with and how to manage flow
     *                                  control
     * @param platformConnectionManager manager for connections to AxonServer platform
     * @param snapshotSerializer        The serializer to serialize Snapshot payloads with
     * @param eventSerializer           The serializer to serialize Event payloads with
     * @param upcasterChain             The upcaster to modify received Event representations with
     */
    public AxonServerEventStore(AxonServerConfiguration configuration,
                                PlatformConnectionManager platformConnectionManager,
                                Serializer snapshotSerializer,
                                Serializer eventSerializer,
                                EventUpcaster upcasterChain) {
        super(AxonIQEventStorageEngine.builder()
                                      .snapshotSerializer(snapshotSerializer)
                                      .upcasterChain(upcasterChain)
                                      .eventSerializer(eventSerializer)
                                      .configuration(configuration)
                                      .eventStoreClient(new AxonDBClient(configuration, platformConnectionManager))
                                      .converter(new GrpcMetaDataConverter(eventSerializer))
                                      .build());
    }

    @Override
    public TrackingEventStream openStream(TrackingToken trackingToken) {
        return storageEngine().openStream(trackingToken);
    }

    public QueryResultStream query(String query, boolean liveUpdates) {
        return storageEngine().query(query, liveUpdates);
    }

    @Override
    protected AxonIQEventStorageEngine storageEngine() {
        return (AxonIQEventStorageEngine) super.storageEngine();
    }

    private static class AxonIQEventStorageEngine extends AbstractEventStorageEngine {

        private static final int ALLOW_SNAPSHOTS_MAGIC_VALUE = -42;
        private final String APPEND_EVENT_TRANSACTION = this + "/APPEND_EVENT_TRANSACTION";

        private final AxonServerConfiguration configuration;
        private final AxonDBClient eventStoreClient;
        private final GrpcMetaDataConverter converter;

        private AxonIQEventStorageEngine(Builder builder) {
            super(builder);
            this.configuration = builder.configuration;
            this.eventStoreClient = builder.eventStoreClient;
            this.converter = builder.converter;
        }

        private static Builder builder() {
            return new Builder();
        }

        @Override
        protected void appendEvents(List<? extends EventMessage<?>> events, Serializer serializer) {
            AppendEventTransaction sender;
            if (CurrentUnitOfWork.isStarted()) {
                sender = CurrentUnitOfWork.get().root().getOrComputeResource(APPEND_EVENT_TRANSACTION, k -> {
                    AppendEventTransaction appendEventTransaction = eventStoreClient.createAppendEventConnection();
                    CurrentUnitOfWork.get().root().onRollback(
                            u -> appendEventTransaction.rollback(u.getExecutionResult().getExceptionResult())
                    );
                    CurrentUnitOfWork.get().root().onCommit(u -> commit(appendEventTransaction));
                    return appendEventTransaction;
                });
            } else {
                sender = eventStoreClient.createAppendEventConnection();
            }
            for (EventMessage<?> eventMessage : events) {
                sender.append(map(eventMessage, serializer));
            }
            if (!CurrentUnitOfWork.isStarted()) {
                commit(sender);
            }
        }

        private void commit(AppendEventTransaction appendEventTransaction) {
            try {
                appendEventTransaction.commit();
            } catch (ExecutionException e) {
                throw ErrorCode.convert(e.getCause());
            } catch (TimeoutException | InterruptedException e) {
                throw ErrorCode.convert(e);
            }
        }

        public Event map(EventMessage<?> eventMessage, Serializer serializer) {
            Event.Builder builder = Event.newBuilder();
            if (eventMessage instanceof GenericDomainEventMessage) {
                builder.setAggregateIdentifier(((GenericDomainEventMessage) eventMessage).getAggregateIdentifier())
                       .setAggregateSequenceNumber(((GenericDomainEventMessage) eventMessage).getSequenceNumber())
                       .setAggregateType(((GenericDomainEventMessage) eventMessage).getType());
            }
            SerializedObject<byte[]> serializedPayload = eventMessage.serializePayload(serializer, byte[].class);
            builder.setMessageIdentifier(eventMessage.getIdentifier()).setPayload(
                    io.axoniq.axonserver.grpc.SerializedObject.newBuilder()
                                                              .setType(serializedPayload.getType().getName())
                                                              .setRevision(getOrDefault(
                                                                      serializedPayload.getType().getRevision(),
                                                                      ""
                                                              ))
                                                              .setData(ByteString.copyFrom(serializedPayload.getData()))
            ).setTimestamp(eventMessage.getTimestamp().toEpochMilli());
            eventMessage.getMetaData().forEach((k, v) -> builder.putMetaData(k, converter.convertToMetaDataValue(v)));
            return builder.build();
        }


        @Override
        protected void storeSnapshot(DomainEventMessage<?> snapshot, Serializer serializer) {
            try {
                eventStoreClient.appendSnapshot(map(snapshot, serializer)).whenComplete((c, e) -> {
                    if (e != null) {
                        logger.warn("Error occurred while creating a snapshot", e);
                    } else if (c != null) {
                        if (c.getSuccess()) {
                            logger.info("Snapshot created");
                        } else {
                            logger.warn("Snapshot creation failed for unknown reason. Check server logs for details.");
                        }
                    }
                });
            } catch (Throwable e) {
                throw ErrorCode.convert(e);
            }
        }

        @Override
        protected Stream<? extends DomainEventData<?>> readEventData(String aggregateIdentifier,
                                                                     long firstSequenceNumber) {
            logger.debug("Reading events for aggregate id {}", aggregateIdentifier);
            GetAggregateEventsRequest.Builder request = GetAggregateEventsRequest.newBuilder()
                                                                                 .setAggregateId(aggregateIdentifier);
            if (firstSequenceNumber > 0) {
                request.setInitialSequence(firstSequenceNumber);
            } else if (firstSequenceNumber == ALLOW_SNAPSHOTS_MAGIC_VALUE) {
                request.setAllowSnapshots(true);
            }
            try {
                return eventStoreClient.listAggregateEvents(request.build()).map(GrpcBackedDomainEventData::new);
            } catch (Exception e) {
                throw ErrorCode.convert(e);
            }
        }

        public TrackingEventStream openStream(TrackingToken trackingToken) {
            Assert.isTrue(trackingToken == null || trackingToken instanceof GlobalSequenceTrackingToken,
                          () -> "Invalid tracking token type. Must be GlobalSequenceTrackingToken.");
            long nextToken = trackingToken == null
                    ? 0
                    : ((GlobalSequenceTrackingToken) trackingToken).getGlobalIndex() + 1;
            EventBuffer consumer = new EventBuffer(upcasterChain, getEventSerializer());

            logger.info("open stream: {}", nextToken);

            StreamObserver<GetEventsRequest> requestStream = eventStoreClient
                    .listEvents(new StreamObserver<EventWithToken>() {
                        @Override
                        public void onNext(EventWithToken eventWithToken) {
                            logger.debug("Received event with token: {}", eventWithToken.getToken());
                            consumer.push(eventWithToken);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            consumer.fail(new EventStoreException(
                                    "Error while reading events from the server", throwable
                            ));
                        }

                        @Override
                        public void onCompleted() {
                            consumer.fail(new EventStoreException("Error while reading events from the server",
                                                                  new RuntimeException("Connection closed by server")));
                        }
                    });
            FlowControllingStreamObserver<GetEventsRequest> observer = new FlowControllingStreamObserver<>(
                    requestStream,
                    configuration,
                    t -> GetEventsRequest.newBuilder().setNumberOfPermits(t.getPermits()).build(),
                    t -> false
            );

            GetEventsRequest request = GetEventsRequest.newBuilder()
                                                       .setTrackingToken(nextToken)
                                                       .setClient(configuration.getClientName())
                                                       .setComponent(configuration.getComponentName())
                                                       .setNumberOfPermits(configuration.getInitialNrOfPermits())
                                                       .build();
            observer.onNext(request);

            consumer.registerCloseListener((eventConsumer) -> observer.onCompleted());
            consumer.registerConsumeListener(observer::markConsumed);
            return consumer;
        }

        public QueryResultStream query(String query, boolean liveUpdates) {
            QueryResultBuffer consumer = new QueryResultBuffer();

            logger.debug("query: {}", query);
            StreamObserver<QueryEventsRequest> requestStream = eventStoreClient
                    .query(new StreamObserver<QueryEventsResponse>() {
                        @Override
                        public void onNext(QueryEventsResponse eventWithToken) {
                            consumer.push(eventWithToken);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            logger.info("Failed to receive events - {}", throwable.getMessage());
                            consumer.fail(new EventStoreException("Error while reading query results from the server",
                                                                  throwable));
                        }

                        @Override
                        public void onCompleted() {
                            consumer.close();
                        }
                    });
            FlowControllingStreamObserver<QueryEventsRequest> observer = new FlowControllingStreamObserver<>(
                    requestStream,
                    configuration,
                    t -> QueryEventsRequest.newBuilder().setNumberOfPermits(t.getPermits()).build(),
                    t -> false
            );

            observer.onNext(QueryEventsRequest.newBuilder()
                                              .setQuery(query)
                                              .setNumberOfPermits(configuration.getInitialNrOfPermits())
                                              .setLiveEvents(liveUpdates)
                                              .build());

            consumer.registerCloseListener((eventConsumer) -> observer.onCompleted());
            consumer.registerConsumeListener(observer::markConsumed);
            return consumer;
        }


        @Override
        public DomainEventStream readEvents(String aggregateIdentifier) {
            Stream<? extends DomainEventData<?>> input =
                    this.readEventData(aggregateIdentifier, ALLOW_SNAPSHOTS_MAGIC_VALUE);
            return DomainEventStream.of(input.map(this::upcastAndDeserializeDomainEvent).filter(Objects::nonNull));
        }

        private DomainEventMessage<?> upcastAndDeserializeDomainEvent(DomainEventData<?> domainEventData) {
            DomainEventStream upcastedStream = EventUtils.upcastAndDeserializeDomainEvents(
                    Stream.of(domainEventData),
                    new GrpcMetaDataAwareSerializer(isSnapshot(domainEventData)
                                                            ? getSnapshotSerializer()
                                                            : getEventSerializer()),
                    upcasterChain,
                    false
            );
            return upcastedStream.hasNext() ? upcastedStream.next() : null;
        }

        private boolean isSnapshot(DomainEventData<?> domainEventData) {
            if (domainEventData instanceof GrpcBackedDomainEventData) {
                GrpcBackedDomainEventData grpcBackedDomainEventData = (GrpcBackedDomainEventData) domainEventData;
                return grpcBackedDomainEventData.isSnapshot();
            }
            return false;
        }

        @Override
        public Optional<Long> lastSequenceNumberFor(String aggregateIdentifier) {
            try {
                ReadHighestSequenceNrResponse lastSequenceNumber = eventStoreClient
                        .lastSequenceNumberFor(aggregateIdentifier).get();
                return lastSequenceNumber.getToSequenceNr() < 0
                        ? Optional.empty()
                        : Optional.of(lastSequenceNumber.getToSequenceNr());
            } catch (Throwable e) {
                throw ErrorCode.convert(e);
            }
        }

        @Override
        public TrackingToken createTailToken() {
            try {
                io.axoniq.axonserver.grpc.event.TrackingToken token = eventStoreClient.getFirstToken().get();
                if (token.getToken() < 0) {
                    return null;
                }
                return new GlobalSequenceTrackingToken(token.getToken() - 1);
            } catch (Throwable e) {
                throw ErrorCode.convert(e);
            }
        }

        @Override
        public TrackingToken createHeadToken() {
            try {
                io.axoniq.axonserver.grpc.event.TrackingToken token = eventStoreClient.getLastToken().get();
                return new GlobalSequenceTrackingToken(token.getToken());
            } catch (Throwable e) {
                throw ErrorCode.convert(e);
            }
        }

        @Override
        public TrackingToken createTokenAt(Instant instant) {
            try {
                io.axoniq.axonserver.grpc.event.TrackingToken token = eventStoreClient.getTokenAt(instant).get();
                if (token.getToken() < 0) {
                    return null;
                }
                return new GlobalSequenceTrackingToken(token.getToken() - 1);
            } catch (Throwable e) {
                throw ErrorCode.convert(e);
            }
        }

        @Override
        protected Stream<? extends TrackedEventData<?>> readEventData(TrackingToken trackingToken, boolean mayBlock) {
            throw new UnsupportedOperationException(
                    "This method is not optimized for the AxonIQ Event Store and should not be used"
            );
        }

        @Override
        protected Stream<? extends DomainEventData<?>> readSnapshotData(String aggregateIdentifier) {
            // Snapshots are automatically fetched server-side, which is faster
            return Stream.empty();
        }

        private static class Builder extends AbstractEventStorageEngine.Builder {

            private AxonServerConfiguration configuration;
            private AxonDBClient eventStoreClient;
            private GrpcMetaDataConverter converter;

            @Override
            public Builder snapshotSerializer(Serializer snapshotSerializer) {
                super.snapshotSerializer(snapshotSerializer);
                return this;
            }

            @Override
            public Builder upcasterChain(EventUpcaster upcasterChain) {
                super.upcasterChain(upcasterChain);
                return this;
            }

            @Override
            public Builder persistenceExceptionResolver(PersistenceExceptionResolver persistenceExceptionResolver) {
                super.persistenceExceptionResolver(persistenceExceptionResolver);
                return this;
            }

            @Override
            public Builder eventSerializer(Serializer eventSerializer) {
                super.eventSerializer(eventSerializer);
                return this;
            }

            @Override
            public Builder snapshotFilter(Predicate<? super DomainEventData<?>> snapshotFilter) {
                super.snapshotFilter(snapshotFilter);
                return this;
            }

            private Builder configuration(AxonServerConfiguration configuration) {
                assertNonNull(configuration, "AxonServerConfiguration may not be null");
                this.configuration = configuration;
                return this;
            }

            private Builder eventStoreClient(AxonDBClient eventStoreClient) {
                assertNonNull(eventStoreClient, "AxonDBClient may not be null");
                this.eventStoreClient = eventStoreClient;
                return this;
            }

            private Builder converter(GrpcMetaDataConverter converter) {
                assertNonNull(converter, "GrpcMetaDataConverter may not be null");
                this.converter = converter;
                return this;
            }

            private AxonIQEventStorageEngine build() {
                return new AxonIQEventStorageEngine(this);
            }

            @Override
            protected void validate() throws AxonConfigurationException {
                assertNonNull(configuration,
                              "The AxonServerConfiguration is a hard requirement and should be provided");
                assertNonNull(eventStoreClient, "The AxonDBClient is a hard requirement and should be provided");
                assertNonNull(converter, "The GrpcMetaDataConverter is a hard requirement and should be provided");
            }
        }
    }
}
