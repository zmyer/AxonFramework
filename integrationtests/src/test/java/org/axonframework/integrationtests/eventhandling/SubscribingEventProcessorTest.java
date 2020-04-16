/*
 * Copyright (c) 2010-2018. Axon Framework
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

package org.axonframework.integrationtests.eventhandling;

import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.common.transaction.Transaction;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.EventHandlerInvoker;
import org.axonframework.eventhandling.EventMessageHandler;
import org.axonframework.eventhandling.SimpleEventHandlerInvoker;
import org.axonframework.eventhandling.SubscribingEventProcessor;
import org.axonframework.integrationtests.utils.EventTestUtils;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SubscribingEventProcessorTest {

    private SubscribingEventProcessor testSubject;
    private EmbeddedEventStore eventBus;
    private EventHandlerInvoker eventHandlerInvoker;
    private EventMessageHandler mockHandler;
    private TestingTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        mockHandler = mock(EventMessageHandler.class);
        eventHandlerInvoker = SimpleEventHandlerInvoker.builder().eventHandlers(mockHandler).build();
        eventBus = EmbeddedEventStore.builder().storageEngine(new InMemoryEventStorageEngine()).build();
        transactionManager = new TestingTransactionManager();
        testSubject = SubscribingEventProcessor.builder()
                                               .name("test")
                                               .eventHandlerInvoker(eventHandlerInvoker)
                                               .messageSource(eventBus)
                                               .transactionManager(transactionManager)
                                               .build();
    }

    @AfterEach
    void tearDown() {
        testSubject.shutDown();
        eventBus.shutDown();
    }

    @Test
    void testRestartSubscribingEventProcessor() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        doAnswer(invocation -> {
            countDownLatch.countDown();
            return null;
        }).when(mockHandler).handle(any());

        testSubject.start();
        testSubject.shutDown();
        testSubject.start();

        eventBus.publish(EventTestUtils.createEvents(2));
        assertTrue(countDownLatch.await(5, TimeUnit.SECONDS), "Expected Handler to have received 2 published events");
    }

    @Test
    void testStartTransactionManager() throws Exception {
        testSubject.start();
        eventBus.publish(EventTestUtils.createEvents(1));

        assertTrue(transactionManager.started, "Expected Transaction to be started");
    }

    static class TestingTransactionManager implements TransactionManager {
        private boolean started;

        @Override
        public Transaction startTransaction() {
            started  = true;
            return NoTransactionManager.INSTANCE.startTransaction();
        }
    }

}
