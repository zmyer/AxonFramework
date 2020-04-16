/*
 * Copyright (c) 2010-2020. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.config;

import org.axonframework.commandhandling.AsynchronousCommandBus;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.callbacks.FutureCallback;
import org.axonframework.common.caching.WeakReferenceCache;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.common.transaction.Transaction;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.EventMessageHandler;
import org.axonframework.eventhandling.TrackingEventProcessor;
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration;
import org.axonframework.eventhandling.async.FullConcurrencyPolicy;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventsourcing.CachingEventSourcingRepository;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine;
import org.axonframework.lifecycle.LifecycleHandlerInvocationException;
import org.axonframework.messaging.GenericMessage;
import org.axonframework.messaging.interceptors.TransactionManagingInterceptor;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.GenericJpaRepository;
import org.axonframework.modelling.command.VersionedAggregateIdentifier;
import org.axonframework.queryhandling.QueryUpdateEmitter;
import org.axonframework.queryhandling.SimpleQueryUpdateEmitter;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Id;
import javax.persistence.Persistence;

import static org.axonframework.config.AggregateConfigurer.defaultConfiguration;
import static org.axonframework.config.AggregateConfigurer.jpaMappedConfiguration;
import static org.axonframework.config.ConfigAssertions.assertExpectedModules;
import static org.axonframework.config.utils.AssertUtils.assertRetryingWithin;
import static org.axonframework.modelling.command.AggregateLifecycle.apply;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating several {@link DefaultConfigurer} operations.
 *
 * @author Allard Buijze
 */
class DefaultConfigurerTest {

    private EntityManager em;

    @BeforeEach
    void setUp() {
        Map<String, String> properties = new HashMap<>();
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:axontest");
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("eventStore", properties);
        em = emf.createEntityManager();
    }

    @AfterEach
    void tearDown() {
        em.close();
    }

    @Test
    void defaultConfigurationWithEventSourcing() throws Exception {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .configureCommandBus(c -> AsynchronousCommandBus.builder().build())
                                                .configureAggregate(StubAggregate.class)
                                                .buildConfiguration();
        config.start();

        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get().getPayload());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(EventSourcingRepository.class, config.repository(StubAggregate.class).getClass());
        assertEquals(1, config.getModules().size());
        assertExpectedModules(config,
                              AggregateConfiguration.class);
    }

    @Test
    void defaultConfigurationWithTrackingProcessorConfigurationInMainConfig() {
        Configurer configurer = DefaultConfigurer.defaultConfiguration();
        configurer.eventProcessing()
                  .registerEventHandler(c -> (EventMessageHandler) event -> null);
        Configuration config = configurer
                .registerComponent(TrackingEventProcessorConfiguration.class,
                                   c -> TrackingEventProcessorConfiguration.forParallelProcessing(2))
                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                .start();
        try {
            TrackingEventProcessor processor = config.eventProcessingConfiguration().eventProcessor(getClass().getPackage().getName(), TrackingEventProcessor.class)
                                                     .orElseThrow(RuntimeException::new);
            assertRetryingWithin(Duration.ofSeconds(5), () -> assertEquals(2, config.getComponent(TokenStore.class)
                    .fetchSegments(processor.getName()).length));
        } finally {
            config.shutdown();
        }
    }

    @Test
    void defaultConfigurationWithTrackingProcessorExplicitlyConfigured() {
        Configurer configurer = DefaultConfigurer.defaultConfiguration();
        String processorName = "myProcessor";
        configurer.eventProcessing()
                  .registerTrackingEventProcessor(processorName,
                                                  Configuration::eventStore,
                                                  c -> TrackingEventProcessorConfiguration.forParallelProcessing(2))
                  .byDefaultAssignTo(processorName)
                  .registerDefaultSequencingPolicy(c -> new FullConcurrencyPolicy())
                  .registerEventHandler(c -> (EventMessageHandler) event -> null);
        Configuration config = configurer
                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                .start();
        try {
            TrackingEventProcessor processor = config.eventProcessingConfiguration()
                                                     .eventProcessor(processorName, TrackingEventProcessor.class)
                                                     .orElseThrow(RuntimeException::new);
            assertRetryingWithin(Duration.ofSeconds(5), () -> assertEquals(2, config.getComponent(TokenStore.class)
                    .fetchSegments(processor.getName()).length));
        } finally {
            config.shutdown();
        }
    }

    @Test
    void defaultConfigurationWithUpcaster() {
        AtomicInteger counter = new AtomicInteger();
        Configuration config = DefaultConfigurer.defaultConfiguration().configureEmbeddedEventStore(
                c -> JpaEventStorageEngine.builder()
                                          .snapshotSerializer(c.serializer())
                                          .upcasterChain(c.upcasterChain())
                                          .persistenceExceptionResolver(c.getComponent(PersistenceExceptionResolver.class))
                                          .entityManagerProvider(() -> em)
                                          .transactionManager(c.getComponent(TransactionManager.class))
                                          .build()
        ).configureAggregate(
                defaultConfiguration(StubAggregate.class).configureCommandTargetResolver(
                        c -> command -> new VersionedAggregateIdentifier(command.getPayload().toString(), null)
                )
        ).registerEventUpcaster(c -> events -> {
            counter.incrementAndGet();
            return events;
        }).configureTransactionManager(c -> new EntityManagerTransactionManager(em)).buildConfiguration();

        config.start();

        config.commandGateway().sendAndWait(GenericCommandMessage.asCommandMessage("test"));
        config.commandGateway().sendAndWait(new GenericCommandMessage<>(new GenericMessage<>("test"), "update"));
        assertEquals(1, counter.get());
        assertNotNull(config.repository(StubAggregate.class));
    }

    @Test
    void testJpaConfigurationWithInitialTransactionManagerJpaRepository() throws Exception {
        EntityManagerTransactionManager transactionManager = spy(new EntityManagerTransactionManager(em));
        Configuration config = DefaultConfigurer.jpaConfiguration(
                () -> em, transactionManager).configureCommandBus(c -> {
            AsynchronousCommandBus commandBus = AsynchronousCommandBus.builder().build();
            commandBus.registerHandlerInterceptor(
                    new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class))
            );
            return commandBus;
        }).configureAggregate(
                defaultConfiguration(StubAggregate.class).configureRepository(
                        c -> GenericJpaRepository.builder(StubAggregate.class)
                                .entityManagerProvider(new SimpleEntityManagerProvider(em))
                                .eventBus(c.eventBus())
                                .parameterResolverFactory(c.parameterResolverFactory())
                                .build()
                )
        ).buildConfiguration();

        config.start();
        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get().getPayload());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(1, config.getModules().size());
        assertExpectedModules(config,
                              AggregateConfiguration.class);

        verify(transactionManager, times(2)).startTransaction();
    }

    @Test
    void testJpaConfigurationWithInitialTransactionManagerJpaRepositoryFromConfiguration() throws Exception {
        EntityManagerTransactionManager transactionManager = spy(new EntityManagerTransactionManager(em));
        Configuration config = DefaultConfigurer.jpaConfiguration(() -> em, transactionManager)
                                                .configureCommandBus(c -> {
                                                    AsynchronousCommandBus commandBus =
                                                            AsynchronousCommandBus.builder().build();
                                                    commandBus.registerHandlerInterceptor(new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class)));
                                                    return commandBus;
                                                })
                                                .configureAggregate(jpaMappedConfiguration(StubAggregate.class))
                                                .buildConfiguration();

        config.start();
        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get().getPayload());
        assertNotNull(config.repository(StubAggregate.class));
        assertTrue(config.getModules()
                         .stream()
                         .anyMatch(m -> m instanceof AggregateConfiguration));

        verify(transactionManager, times(2)).startTransaction();
    }

    @Test
    void testMissingEntityManagerProviderIsReported() {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureCommandBus(c -> {
                                                    AsynchronousCommandBus commandBus =
                                                            AsynchronousCommandBus.builder().build();
                                                    commandBus.registerHandlerInterceptor(new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class)));
                                                    return commandBus;
                                                })
                                                .configureAggregate(jpaMappedConfiguration(StubAggregate.class))
                                                .buildConfiguration();

        try {
            config.start();
            fail("Expected LifecycleHandlerInvocationException");
        } catch (LifecycleHandlerInvocationException e) {
            // expected
        }
    }

    @Test
    void testJpaConfigurationWithJpaRepository() throws Exception {
        EntityManagerTransactionManager transactionManager = spy(new EntityManagerTransactionManager(em));
        Configuration config = DefaultConfigurer.jpaConfiguration(() -> em).registerComponent(
                TransactionManager.class, c -> transactionManager
        ).configureCommandBus(c -> {
            AsynchronousCommandBus commandBus = AsynchronousCommandBus.builder().build();
            commandBus.registerHandlerInterceptor(
                    new TransactionManagingInterceptor<>(c.getComponent(TransactionManager.class))
            );
            return commandBus;
        }).configureAggregate(
                defaultConfiguration(StubAggregate.class).configureRepository(
                        c -> GenericJpaRepository.builder(StubAggregate.class)
                                .entityManagerProvider(new SimpleEntityManagerProvider(em))
                                .eventBus(c.eventBus())
                                .parameterResolverFactory(c.parameterResolverFactory())
                                .build()
                )
        ).buildConfiguration();

        config.start();
        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get().getPayload());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(1, config.getModules().size());
        assertExpectedModules(config,
                              AggregateConfiguration.class);

        verify(transactionManager, times(2)).startTransaction();
    }

    @Test
    void defaultConfigurationWithMonitors() throws Exception {
        MessageCollectingMonitor defaultMonitor = new MessageCollectingMonitor();
        MessageCollectingMonitor commandBusMonitor = new MessageCollectingMonitor();

        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .configureAggregate(StubAggregate.class)
                                                .configureMessageMonitor(c -> (t, n) -> defaultMonitor)
                                                .configureMessageMonitor(CommandBus.class, "commandBus", c -> commandBusMonitor)
                                                .buildConfiguration();
        config.start();

        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get().getPayload());
        assertEquals(1, defaultMonitor.getMessages().size());
        assertEquals(1, commandBusMonitor.getMessages().size());
    }

    @Test
    void testRegisterSeveralModules() {
        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureAggregate(StubAggregate.class)
                                                .configureAggregate(Object.class)
                                                .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
                                                .start();

        assertEquals(2, config.getModules().size());
        assertExpectedModules(config,
                              AggregateConfiguration.class,
                              AggregateConfiguration.class);
    }

    @Test
    void testQueryUpdateEmitterConfigurationPropagatedToTheQueryBus() {
        QueryUpdateEmitter queryUpdateEmitter = SimpleQueryUpdateEmitter.builder().build();
        Configuration configuration = DefaultConfigurer.defaultConfiguration()
                                                       .configureQueryUpdateEmitter(c -> queryUpdateEmitter)
                                                       .buildConfiguration();
        assertEquals(queryUpdateEmitter, configuration.queryBus().queryUpdateEmitter());
        assertEquals(queryUpdateEmitter, configuration.queryUpdateEmitter());
    }

    @Test
    public void defaultConfigurationWithCache() throws Exception {
        Configuration config = DefaultConfigurer.defaultConfiguration()
            .configureEmbeddedEventStore(c -> new InMemoryEventStorageEngine())
            .configureCommandBus(c -> AsynchronousCommandBus.builder().build())
            .configureAggregate(
                defaultConfiguration(StubAggregate.class).configureCache(c-> new WeakReferenceCache())
             )
            .buildConfiguration();
        config.start();

        FutureCallback<Object, Object> callback = new FutureCallback<>();
        config.commandBus().dispatch(GenericCommandMessage.asCommandMessage("test"), callback);
        assertEquals("test", callback.get().getPayload());
        assertNotNull(config.repository(StubAggregate.class));
        assertEquals(CachingEventSourcingRepository.class, config.repository(StubAggregate.class).getClass());
    }

    @SuppressWarnings("unused")
    @Entity(name = "StubAggregate")
    private static class StubAggregate {

        @SuppressWarnings("FieldCanBeLocal")
        @Id
        @AggregateIdentifier
        private String id;

        public StubAggregate() {
        }

        @CommandHandler
        public StubAggregate(String command, CommandBus commandBus) {
            apply(command);
        }

        @CommandHandler(commandName = "update")
        public void update(String command) {
            apply(1L);
        }

        @EventSourcingHandler
        protected void on(String event) {
            this.id = event;
        }
    }

    private static class EntityManagerTransactionManager implements TransactionManager {

        private final EntityManager em;

        public EntityManagerTransactionManager(EntityManager em) {
            this.em = em;
        }

        @Override
        public Transaction startTransaction() {
            EntityTransaction tx = em.getTransaction();
            if (tx.isActive()) {
                return new Transaction() {
                    @Override
                    public void commit() {
                    }

                    @Override
                    public void rollback() {
                    }
                };
            }
            tx.begin();
            return new Transaction() {
                @Override
                public void commit() {
                    tx.commit();
                }

                @Override
                public void rollback() {
                    tx.rollback();
                }
            };
        }
    }
}
