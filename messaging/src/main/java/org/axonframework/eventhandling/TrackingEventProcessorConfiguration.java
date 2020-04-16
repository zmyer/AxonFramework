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

package org.axonframework.eventhandling;

import org.axonframework.common.Assert;
import org.axonframework.common.AxonThreadFactory;
import org.axonframework.messaging.StreamableMessageSource;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.axonframework.common.BuilderUtils.assertThat;

/**
 * Configuration object for the {@link TrackingEventProcessor}. The TrackingEventProcessorConfiguration provides access
 * to the options to tweak various settings. Instances are not thread-safe and should not be altered after they have
 * been used to initialize a TrackingEventProcessor.
 *
 * @author Christophe Bouhier
 * @author Allard Buijze
 * @since 3.1
 */
public class TrackingEventProcessorConfiguration {

    private static final int DEFAULT_BATCH_SIZE = 1;
    private static final int DEFAULT_THREAD_COUNT = 1;
    private static final int DEFAULT_TOKEN_CLAIM_INTERVAL = 5000;

    private final int maxThreadCount;
    private int batchSize;
    private int initialSegmentCount;
    private Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenBuilder = StreamableMessageSource::createTailToken;
    private Function<String, ThreadFactory> threadFactory;
    private long tokenClaimInterval;
    private int eventAvailabilityTimeout = 1000;

    /**
     * Initialize a configuration with single threaded processing.
     *
     * @return a Configuration prepared for single threaded processing
     */
    public static TrackingEventProcessorConfiguration forSingleThreadedProcessing() {
        return new TrackingEventProcessorConfiguration(DEFAULT_THREAD_COUNT);
    }

    /**
     * Initialize a configuration instance with the given {@code threadCount}. This is both the number of threads that a
     * processor will start for processing, as well as the initial number of segments that will be created when the
     * processor is first started.
     *
     * @param threadCount the number of segments to process in parallel
     * @return a newly created configuration
     */
    public static TrackingEventProcessorConfiguration forParallelProcessing(int threadCount) {
        return new TrackingEventProcessorConfiguration(threadCount);
    }

    private TrackingEventProcessorConfiguration(int numberOfSegments) {
        this.batchSize = DEFAULT_BATCH_SIZE;
        this.initialSegmentCount = numberOfSegments;
        this.maxThreadCount = numberOfSegments;
        this.threadFactory = pn -> new AxonThreadFactory("EventProcessor[" + pn + "]");
        this.tokenClaimInterval = DEFAULT_TOKEN_CLAIM_INTERVAL;
    }

    /**
     * @param batchSize The maximum number of events to process in a single batch.
     * @return {@code this} for method chaining
     */
    public TrackingEventProcessorConfiguration andBatchSize(int batchSize) {
        Assert.isTrue(batchSize > 0, () -> "Batch size must be greater or equal to 1");
        this.batchSize = batchSize;
        return this;
    }

    /**
     * @param segmentsSize The number of segments requested for handling asynchronous processing of events.
     * @return {@code this} for method chaining
     */
    public TrackingEventProcessorConfiguration andInitialSegmentsCount(int segmentsSize) {
        this.initialSegmentCount = segmentsSize;
        return this;
    }

    /**
     * Sets the ThreadFactory to use to create the threads to process events on. Each Segment will be processed by a
     * separate thread.
     *
     * @param threadFactory The factory to create threads with
     * @return {@code this} for method chaining
     */
    public TrackingEventProcessorConfiguration andThreadFactory(Function<String, ThreadFactory> threadFactory) {
        this.threadFactory = threadFactory;
        return this;
    }

    /**
     * Set the duration where a Tracking Processor will wait for the availability of Events, in each cycle, before
     * extending the claim on the tokens it owns.
     * <p>
     * Note that some storage engines for the EmbeddedEventStore do not support streaming. They may poll for messages
     * once on an {@link TrackingEventStream#hasNextAvailable(int, TimeUnit)} invocation, and wait for the timeout to
     * occur.
     * <p>
     * This value should be significantly shorter than the claim timeout configured on the Token Store. Failure to do so
     * may cause claims to be stolen while a tread is waiting for events. Also, with very long timeouts, it will take
     * longer for threads to pick up the instructions they need to process.
     * <p>
     * Defaults to 1 second.
     * <p>
     * The given value must be strictly larger than 0, and may not exceed {@code Integer.MAX_VALUE} milliseconds.
     *
     * @param interval The interval in which claims on segments need to be extended
     * @param unit     The unit in which the interval is expressed
     * @return {@code this} for method chaining
     */
    public TrackingEventProcessorConfiguration andEventAvailabilityTimeout(long interval, TimeUnit unit) {
        long i = unit.toMillis(interval);
        assertThat(i, it -> it <= Integer.MAX_VALUE,
                   "Interval may not be longer than Integer.MAX_VALUE milliseconds long");
        assertThat(i, it -> it > 0, "Interval must be strictly positive");
        this.eventAvailabilityTimeout = (int) i;
        return this;
    }

    /**
     * Sets the Builder to use to create the initial tracking token. This token is used by the processor as a starting
     * point.
     *
     * @param initialTrackingTokenBuilder The Builder of initial tracking token
     * @return {@code this} for method chaining
     */
    public TrackingEventProcessorConfiguration andInitialTrackingToken(
            Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> initialTrackingTokenBuilder) {
        this.initialTrackingTokenBuilder = initialTrackingTokenBuilder;
        return this;
    }

    /**
     * Sets the time to wait after a failed attempt to claim any token, before making another attempt.
     *
     * @param tokenClaimInterval The time to wait in between attempts to claim a token
     * @param timeUnit           The unit of time
     * @return {@code this} for method chaining
     */
    public TrackingEventProcessorConfiguration andTokenClaimInterval(long tokenClaimInterval, TimeUnit timeUnit) {
        this.tokenClaimInterval = timeUnit.toMillis(tokenClaimInterval);
        return this;
    }

    /**
     * @return the maximum number of events to process in a single batch.
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * @return the number of segments requested for handling asynchronous processing of events.
     */
    public int getInitialSegmentsCount() {
        return initialSegmentCount;
    }

    /**
     * @return the Builder of initial tracking token
     */
    public Function<StreamableMessageSource<TrackedEventMessage<?>>, TrackingToken> getInitialTrackingToken() {
        return initialTrackingTokenBuilder;
    }

    /**
     * @return the pool size of core threads as per {@link ThreadPoolExecutor#getCorePoolSize()}
     */
    public int getMaxThreadCount() {
        return maxThreadCount;
    }

    /**
     * @return the time, in milliseconds, that a processor should wait for available events before going into a cycle of
     * updating claims and checking for incoming instructions.
     */
    public int getEventAvailabilityTimeout() {
        return eventAvailabilityTimeout;
    }

    /**
     * Provides the ThreadFactory to use to construct Threads for the processor with given {@code processorName}
     *
     * @param processorName The name of the processor for which to return the ThreadFactory
     * @return the thread factory configured
     */
    public ThreadFactory getThreadFactory(String processorName) {
        return threadFactory.apply(processorName);
    }

    /**
     * Returns the time, in milliseconds, the processor should wait after a failed attempt to claim any segments for
     * processing. Generally, this means all segments are claimed.
     *
     * @return the time, in milliseconds, to wait in between attempts to claim a token
     * @see #andTokenClaimInterval(long, TimeUnit)
     */
    public long getTokenClaimInterval() {
        return tokenClaimInterval;
    }
}
