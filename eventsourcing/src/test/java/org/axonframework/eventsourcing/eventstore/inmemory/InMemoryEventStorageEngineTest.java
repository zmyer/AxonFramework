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

package org.axonframework.eventsourcing.eventstore.inmemory;

import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventsourcing.eventstore.EventStorageEngineTest;
import org.junit.jupiter.api.*;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Rene de Waele
 */
class InMemoryEventStorageEngineTest extends EventStorageEngineTest {

    private InMemoryEventStorageEngine testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new InMemoryEventStorageEngine();
        setTestSubject(testSubject);
    }

    @Test
    void testPublishedEventsEmittedToExistingStreams() {
        Stream<? extends TrackedEventMessage<?>> stream = testSubject.readEvents(null, true);
        testSubject.appendEvents(GenericEventMessage.asEventMessage("test"));

        assertTrue(stream.findFirst().isPresent());
    }
}
