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

package org.axonframework.axonserver.connector;

import org.axonframework.axonserver.connector.command.AxonServerCommandBus;
import org.axonframework.axonserver.connector.event.axon.AxonServerEventStore;
import org.axonframework.axonserver.connector.event.axon.EventProcessorInfoConfiguration;
import org.axonframework.axonserver.connector.query.AxonServerQueryBus;
import org.axonframework.config.Configuration;
import org.axonframework.config.DefaultConfigurer;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.messaging.Message;
import org.junit.*;

import static org.junit.Assert.*;

public class ServerConnectorConfigurerModuleTest {

    @Test
    public void testAxonServerConfiguredInDefaultConfiguration() {
        Configuration testSubject = DefaultConfigurer.defaultConfiguration()
                                                     .buildConfiguration();

        AxonServerConfiguration resultAxonServerConfig = testSubject.getComponent(AxonServerConfiguration.class);

        assertEquals("localhost", resultAxonServerConfig.getServers());
        assertNotNull(resultAxonServerConfig.getClientId());
        assertNotNull(resultAxonServerConfig.getComponentName());
        assertTrue(resultAxonServerConfig.getComponentName().contains(resultAxonServerConfig.getClientId()));

        assertNotNull(testSubject.getComponent(AxonServerConnectionManager.class));
        assertTrue(
                testSubject.getModules().stream()
                           .anyMatch(moduleConfig -> moduleConfig.isType(EventProcessorInfoConfiguration.class))
        );
        assertTrue(testSubject.eventStore() instanceof AxonServerEventStore);
        assertTrue(testSubject.commandBus() instanceof AxonServerCommandBus);
        assertTrue(testSubject.queryBus() instanceof AxonServerQueryBus);

        //noinspection unchecked
        TargetContextResolver<Message<?>> resultTargetContextResolver =
                testSubject.getComponent(TargetContextResolver.class);
        assertNotNull(resultTargetContextResolver);
        // The default TargetContextResolver is a no-op which returns null
        assertNull(resultTargetContextResolver.resolveContext(GenericEventMessage.asEventMessage("some-event")));
    }

    @Test
    public void testQueryUpdateEmitterIsTakenFromConfiguration() {
        Configuration configuration = DefaultConfigurer.defaultConfiguration()
                                                       .buildConfiguration();

        assertTrue(configuration.queryBus() instanceof AxonServerQueryBus);
        assertSame(configuration.queryBus().queryUpdateEmitter(), configuration.queryUpdateEmitter());
        assertSame(((AxonServerQueryBus) configuration.queryBus()).localSegment().queryUpdateEmitter(),
                   configuration.queryUpdateEmitter());
    }
}
