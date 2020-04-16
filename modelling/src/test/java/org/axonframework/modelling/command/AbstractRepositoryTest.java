/*
 * Copyright (c) 2010-2019. Axon Framework
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

package org.axonframework.modelling.command;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.modelling.command.inspection.AnnotatedAggregate;
import org.axonframework.modelling.saga.SagaScopeDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
class AbstractRepositoryTest {

    private static final String AGGREGATE_ID = "some-identifier";

    private AbstractRepository testSubject;

    private AnnotatedAggregate<JpaAggregate> spiedAggregate;
    private Message<?> failureMessage = null;

    @BeforeEach
    void setUp() {
        testSubject = new AbstractRepository<JpaAggregate, AnnotatedAggregate<JpaAggregate>>(
                new AbstractRepository.Builder<JpaAggregate>(JpaAggregate.class) {}) {

            @Override
            protected AnnotatedAggregate<JpaAggregate> doCreateNew(Callable<JpaAggregate> factoryMethod)
                    throws Exception {
                return AnnotatedAggregate.initialize(factoryMethod, aggregateModel(), null);
            }

            @Override
            protected void doSave(AnnotatedAggregate<JpaAggregate> aggregate) {

            }

            @Override
            protected void doDelete(AnnotatedAggregate<JpaAggregate> aggregate) {

            }

            @Override
            protected AnnotatedAggregate<JpaAggregate> doLoad(String aggregateIdentifier, Long expectedVersion) {
                spiedAggregate = spy(AnnotatedAggregate.initialize(new JpaAggregate(), aggregateModel(), null));

                try {
                    doThrow(new IllegalArgumentException()).when(spiedAggregate).handle(failureMessage);
                } catch (Exception e) {
                    // Fail silently for testing's sake
                }

                if (!AGGREGATE_ID.equals(aggregateIdentifier)) {
                    throw new AggregateNotFoundException(aggregateIdentifier, "some-message");
                }

                return spiedAggregate;
            }
        };

        DefaultUnitOfWork.startAndGet(null);
    }

    @AfterEach
    void tearDown() {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    void testAggregateTypeVerification_CorrectType() throws Exception {
        //noinspection unchecked
        testSubject.newInstance(() -> new JpaAggregate("hi"));
    }

    @Test
    void testAggregateTypeVerification_SubclassesAreAllowed() throws Exception {
        //noinspection unchecked
        testSubject.newInstance(() -> new JpaAggregate("hi") {
            // anonymous subclass
        });
    }

    @Test
    void testAggregateTypeVerification_WrongType() throws Exception {
        //noinspection unchecked
        assertThrows(IllegalArgumentException.class, () -> testSubject.newInstance(() -> "Not allowed"));
    }

    @Test
    void testCanResolveReturnsTrueForMatchingAggregateDescriptor() {
        assertTrue(testSubject.canResolve(new AggregateScopeDescriptor(
                JpaAggregate.class.getSimpleName(), AGGREGATE_ID)
        ));
    }

    @Test
    void testCanResolveReturnsFalseNonAggregateScopeDescriptorImplementation() {
        assertFalse(testSubject.canResolve(new SagaScopeDescriptor("some-saga-type", AGGREGATE_ID)));
    }

    @Test
    void testCanResolveReturnsFalseForNonMatchingAggregateType() {
        assertFalse(testSubject.canResolve(new AggregateScopeDescriptor("other-non-matching-type", AGGREGATE_ID)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSendWorksAsExpected() throws Exception {
        DeadlineMessage<String> testMsg = GenericDeadlineMessage.asDeadlineMessage("deadline-name", "payload", Instant.now());
        AggregateScopeDescriptor testDescriptor =
                new AggregateScopeDescriptor(JpaAggregate.class.getSimpleName(), AGGREGATE_ID);

        testSubject.send(testMsg, testDescriptor);

        verify(spiedAggregate).handle(testMsg);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSendThrowsIllegalArgumentExceptionIfHandleFails() throws Exception {
        AggregateScopeDescriptor testDescriptor =
                new AggregateScopeDescriptor(JpaAggregate.class.getSimpleName(), AGGREGATE_ID);

        assertThrows(IllegalArgumentException.class, () -> testSubject.send(failureMessage, testDescriptor));

        verify(spiedAggregate).handle(failureMessage);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSendFailsSilentlyOnAggregateNotFoundException() throws Exception {
        DeadlineMessage<String> testMsg = GenericDeadlineMessage.asDeadlineMessage("deadline-name", "payload", Instant.now());
        AggregateScopeDescriptor testDescriptor =
                new AggregateScopeDescriptor(JpaAggregate.class.getSimpleName(), "some-other-aggregate-id");

        testSubject.send(testMsg, testDescriptor);

        verifyZeroInteractions(spiedAggregate);
    }


    @SuppressWarnings("unchecked")
    @Test
    void testCheckedExceptionFromConstructorDoesNotAttemptToStoreAggregate() throws Exception {
        // committing the unit of work does not throw an exception
        UnitOfWork uow = CurrentUnitOfWork.get();
        uow.executeWithResult(() -> testSubject.newInstance(() -> {
            throw new Exception("Throwing checked exception");
        }), RuntimeException.class::isInstance);

        assertFalse(uow.isActive());
        assertFalse(uow.isRolledBack());
        assertTrue(uow.getExecutionResult().isExceptionResult());
        assertEquals("Throwing checked exception", uow.getExecutionResult().getExceptionResult().getMessage());
    }

}
