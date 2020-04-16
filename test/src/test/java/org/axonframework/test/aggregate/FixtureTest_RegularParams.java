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

package org.axonframework.test.aggregate;

import org.axonframework.modelling.command.AggregateNotFoundException;
import org.axonframework.test.AxonAssertionError;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.axonframework.commandhandling.GenericCommandResultMessage.asCommandResultMessage;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author Allard Buijze
 * @since 0.7
 */
class FixtureTest_RegularParams {

    private FixtureConfiguration<StandardAggregate> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(StandardAggregate.class);
        fixture.registerAggregateFactory(new StandardAggregate.Factory());
    }

    @Test
    void testFixture_NoEventsInStore() {
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                     fixture.getEventBus()))
                .given()
                .when(new TestCommand(UUID.randomUUID()))
                .expectException(AggregateNotFoundException.class);
    }

    @Test
    void testFirstFixture() {
        ResultValidator<StandardAggregate> validator = fixture
                .registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                      fixture.getEventBus()))
                .given(new MyEvent("aggregateId", 1))
                .when(new TestCommand("aggregateId"));
        validator.expectResultMessagePayload(null);
        validator.expectEvents(new MyEvent("aggregateId", 2));
    }

    @Test
    void testFirstFixtureMatchingCommandResultMessage() {
        ResultValidator<StandardAggregate> validator = fixture
                .registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                      fixture.getEventBus()))
                .given(new MyEvent("aggregateId", 1))
                .when(new TestCommand("aggregateId"));
        validator.expectResultMessage(asCommandResultMessage(null));
        validator.expectEvents(new MyEvent("aggregateId", 2));
    }

    @Test
    void testExpectEventsIgnoresFilteredField() {
        ResultValidator<StandardAggregate> validator = fixture
                .registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                      fixture.getEventBus()))
                .registerFieldFilter(field -> !field.getName().equals("someBytes"))
                .given(new MyEvent("aggregateId", 1))
                .when(new TestCommand("aggregateId"));
        validator.expectResultMessagePayload(null);
        validator.expectEvents(new MyEvent("aggregateId", 2, "ignored".getBytes()));
    }

    @Test
    void testFixture_SetterInjection() {
        MyCommandHandler commandHandler = new MyCommandHandler();
        commandHandler.setRepository(fixture.getRepository());
        fixture.registerAnnotatedCommandHandler(commandHandler)
                .given(new MyEvent("aggregateId", 1),
                       new MyEvent("aggregateId", 2))
                .when(new TestCommand("aggregateId"))
                .expectResultMessagePayloadMatching(IsNull.nullValue())
                .expectEvents(new MyEvent("aggregateId", 3));
    }

    @Test
    void testFixture_GivenAList() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        fixture
                .registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                      fixture.getEventBus()))
                .given(givenEvents)
                .when(new TestCommand("aggregateId"))
                .expectEvents(new MyEvent("aggregateId", 4))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    void testFixtureDetectsStateChangeOutsideOfHandler_ExplicitValue() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));

        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                             fixture.getEventBus()))
                       .given(givenEvents)
                       .when(new IllegalStateChangeCommand("aggregateId", 5)));
        assertTrue(e.getMessage().contains(".lastNumber\""), "Wrong message: " + e.getMessage());
        assertTrue(e.getMessage().contains("<5>"), "Wrong message: " + e.getMessage());
        assertTrue(e.getMessage().contains("<4>"), "Wrong message: " + e.getMessage());
    }

    @Test
    void testFixtureIgnoredStateChangeInFilteredField() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        fixture.registerFieldFilter(field -> !field.getName().equals("lastNumber"));
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                     fixture.getEventBus()))
                .given(givenEvents)
                .when(new IllegalStateChangeCommand("aggregateId", 5));
    }

    @Test
    void testFixtureDetectsStateChangeOutsideOfHandler_NullValue() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                             fixture.getEventBus()))
                        .given(givenEvents)
                        .when(new IllegalStateChangeCommand("aggregateId", null)));
        assertTrue(e.getMessage().contains(".lastNumber\""), "Wrong message: " + e.getMessage());
        assertTrue(e.getMessage().contains("<null>"), "Wrong message: " + e.getMessage());
        assertTrue(e.getMessage().contains("<4>"), "Wrong message: " + e.getMessage());
    }

    @Test
    void testFixtureDetectsStateChangeOutsideOfHandler_Ignored() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        fixture.setReportIllegalStateChange(false);
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                     fixture.getEventBus()))
                .given(givenEvents)
                .when(new IllegalStateChangeCommand("aggregateId", null));
    }

    @Test
    void testFixtureDetectsStateChangeOutsideOfHandler_AggregateGeneratesIdentifier() {
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                     fixture.getEventBus()))
                .given()
                .when(new CreateAggregateCommand(null));
    }

    @Test
    void testFixtureDetectsStateChangeOutsideOfHandler_AggregateDeleted() {
        TestExecutor<StandardAggregate> exec = fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                                         fixture.getEventBus()))
                .given(new MyEvent("aggregateId", 5));

        AssertionError error = assertThrows(AssertionError.class,
                () -> exec.when(new DeleteCommand("aggregateId", true)));
        assertTrue(error.getMessage().contains("considered deleted"), "Wrong message: " + error.getMessage());
    }

    @Test
    void testFixture_AggregateDeleted() {
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                     fixture.getEventBus()))
                .given(new MyEvent("aggregateId", 5))
                .when(new DeleteCommand("aggregateId", false))
                .expectEvents(new MyAggregateDeletedEvent(false));
    }

    @Test
    void testFixtureGivenCommands() {
        fixture.registerAnnotatedCommandHandler(new MyCommandHandler(fixture.getRepository(),
                                                                     fixture.getEventBus()))
                .givenCommands(new CreateAggregateCommand("aggregateId"),
                               new TestCommand("aggregateId"),
                               new TestCommand("aggregateId"),
                               new TestCommand("aggregateId"))
                .when(new TestCommand("aggregateId"))
                .expectEvents(new MyEvent("aggregateId", 4));
    }

    @Test
    void testFixture_CommandHandlerDispatchesNonDomainEvents() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        // the domain events are part of the transaction, but the command handler directly dispatches an application
        // event to the event bus. This event dispatched anyway. The
        fixture
                .registerAnnotatedCommandHandler(commandHandler)
                .given(givenEvents)
                .when(new PublishEventCommand("aggregateId"))
                .expectEvents(new MyApplicationEvent());
    }

    @Test
    void testFixture_ReportWrongNumberOfEvents() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture.registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new TestCommand("aggregateId"))
                        .expectEvents(new MyEvent("aggregateId", 4),
                                      new MyEvent("aggregateId", 5))
        );
        assertTrue(e.getMessage().contains("org.axonframework.test.aggregate.MyEvent <|> "));
    }

    @Test
    void testFixture_ReportWrongEvents() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture
                        .registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new TestCommand("aggregateId"))
                        .expectEvents(new MyOtherEvent())
        );
        assertTrue(e.getMessage().contains("org.axonframework.test.aggregate.MyOtherEvent <|>"
                + " org.axonframework.test.aggregate.MyEvent"));
    }

    @Test
    void testFixture_UnexpectedException() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture
                        .registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new StrangeCommand("aggregateId"))
                        .expectSuccessfulHandlerExecution()
        );
        assertTrue(e.getMessage().contains("but got <exception of type [StrangeCommandReceivedException]>"));
    }

    @Test
    void testFixture_UnexpectedReturnValue() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture
                        .registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new TestCommand("aggregateId"))
                        .expectException(RuntimeException.class)
        );
        assertTrue(e.getMessage().contains("The command handler returned normally, but an exception was expected"));
        assertTrue(e.getMessage().contains(
                "<an instance of java.lang.RuntimeException> but returned with <null>"));
    }

    @Test
    void testFixture_WrongReturnValue() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(), fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture.registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new TestCommand("aggregateId"))
                        .expectResultMessagePayload("some")
        );
        assertTrue(e.getMessage().contains("<Message with payload <\"some\">> but got <Message with payload <null>>"), e.getMessage());
    }

    @Test
    void testFixture_WrongExceptionType() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture.registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new StrangeCommand("aggregateId"))
                        .expectException(IOException.class)
        );
        assertTrue(e.getMessage().contains(
                "<an instance of java.io.IOException> but got <exception of type [StrangeCommandReceivedException]>"));
    }

    @Test
    void testFixture_WrongEventContents() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());

        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture
                        .registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new TestCommand("aggregateId"))
                        .expectEvents(new MyEvent("aggregateId", 5)) // should be 4
                        .expectSuccessfulHandlerExecution()
        );
        assertTrue(e.getMessage().contains(
                "In a message of type [MyEvent], the property [someValue] was not as expected."));
        assertTrue(e.getMessage().contains("Expected <5> but got <4>"));
    }

    @Test
    void testFixture_WrongEventContents_WithNullValues() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture
                        .registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new TestCommand("aggregateId"))
                        .expectEvents(new MyEvent("aggregateId", null)) // should be 4
                        .expectSuccessfulHandlerExecution()
        );
        assertTrue(e.getMessage().contains(
                "In a message of type [MyEvent], the property [someValue] was not as expected."));
        assertTrue(e.getMessage().contains("Expected <<null>> but got <4>"));
    }

    @Test
    void testFixture_ExpectedPublishedSameAsStored() {
        List<?> givenEvents = Arrays.asList(new MyEvent("aggregateId", 1),
                                            new MyEvent("aggregateId", 2),
                                            new MyEvent("aggregateId", 3));
        MyCommandHandler commandHandler = new MyCommandHandler(fixture.getRepository(),
                                                               fixture.getEventBus());
        AxonAssertionError e = assertThrows(AxonAssertionError.class, () ->
                fixture
                        .registerAnnotatedCommandHandler(commandHandler)
                        .given(givenEvents)
                        .when(new StrangeCommand("aggregateId"))
                        .expectException(StrangeCommandReceivedException.class)
                        .expectEvents(new MyEvent("aggregateId", 4))
        );
        assertTrue(e.getMessage().contains("The published events do not match the expected events"));
        assertTrue(e.getMessage().contains("org.axonframework.test.aggregate.MyEvent <|> "));
        assertTrue(e.getMessage().contains("probable cause"));
    }
}
