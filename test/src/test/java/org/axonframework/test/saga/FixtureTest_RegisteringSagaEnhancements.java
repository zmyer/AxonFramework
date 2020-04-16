package org.axonframework.test.saga;

import org.axonframework.eventhandling.ListenerInvocationErrorHandler;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.time.Duration.ofSeconds;
import static java.time.Instant.now;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class dedicated to validating custom, saga specific, registered components on {@link SagaTestFixture}.
 *
 * @author Steven van Beelen
 */
class FixtureTest_RegisteringSagaEnhancements {

    private SagaTestFixture<SomeTestSaga> testSubject;

    private AtomicInteger startRecordingCount;

    @BeforeEach
    void setUp() {
        startRecordingCount = new AtomicInteger();

        testSubject = new SagaTestFixture<>(SomeTestSaga.class);
    }

    @Test
    void testStartRecordingCallbackIsInvokedOnWhenPublishingAnEvent() {
        testSubject.registerStartRecordingCallback(startRecordingCount::getAndIncrement)
                   .givenAPublished(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(0));

        testSubject.whenPublishingA(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(1));
    }

    @Test
    void testStartRecordingCallbackIsInvokedOnWhenTimeAdvances() {
        testSubject.registerStartRecordingCallback(startRecordingCount::getAndIncrement)
                   .givenAPublished(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(0));

        testSubject.whenTimeAdvancesTo(now());
        assertThat(startRecordingCount.get(), equalTo(1));
    }

    @Test
    void testStartRecordingCallbackIsInvokedOnWhenTimeElapses() {
        testSubject.registerStartRecordingCallback(startRecordingCount::getAndIncrement)
                   .givenAPublished(new SomeTestSaga.SomeEvent());
        assertThat(startRecordingCount.get(), equalTo(0));

        testSubject.whenTimeElapses(ofSeconds(5));
        assertThat(startRecordingCount.get(), equalTo(1));
    }

    @Test
    void testCustomListenerInvocationErrorHandlerIsUsed() {
        SomeTestSaga.SomeEvent testEvent = new SomeTestSaga.SomeEvent("some-id", true);

        ListenerInvocationErrorHandler testSubject = (exception, event, eventHandler) ->
                assertEquals(testEvent.getException().getMessage(), exception.getMessage());

        this.testSubject.registerListenerInvocationErrorHandler(testSubject);
        // This will trigger the test subject due to how the event is configured
        this.testSubject.givenAPublished(testEvent);
    }

    @Test
    void testRegisteredResourceInjectorIsCalledUponFirstEventPublication() {
        AtomicBoolean assertion = new AtomicBoolean(false);
        testSubject.registerResourceInjector(saga -> assertion.set(true))
                   // Publishing a single event should trigger the creation and injection of resources
                   .givenAPublished(new SomeTestSaga.SomeEvent());

        assertTrue(assertion.get());
    }

    public static class SomeTestSaga {

        @SuppressWarnings("unused")
        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        public void handle(SomeEvent event) throws Exception {
            if (event.shouldThrowException()) {
                throw event.getException();
            }
        }

        public static class SomeEvent {

            private final String id;
            private final Boolean shouldThrowException;
            private final Exception exception = new IllegalStateException("I was told to throw an exception");

            public SomeEvent() {
                this("42", false);
            }

            public SomeEvent(String id, Boolean shouldThrowException) {
                this.id = id;
                this.shouldThrowException = shouldThrowException;
            }

            public String getId() {
                return id;
            }

            Boolean shouldThrowException() {
                return shouldThrowException;
            }

            public Exception getException() {
                return exception;
            }
        }
    }
}
