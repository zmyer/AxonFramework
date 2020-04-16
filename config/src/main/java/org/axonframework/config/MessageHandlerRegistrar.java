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

import org.axonframework.common.Registration;
import org.axonframework.lifecycle.Phase;
import org.axonframework.lifecycle.ShutdownHandler;
import org.axonframework.lifecycle.StartHandler;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * The {@link MessageHandlerRegistrar} manages the lifecycle of a message handling component, by defining a {@link
 * #start()} and {@link #shutdown()} method and keeping hold of the message handler's {@link Registration}.
 * <p>
 * Note that this component is not intended for Event Handling Components, as those should be registered through the
 * {@link EventProcessingConfigurer}.
 *
 * @author Steven van Beelen
 * @since 4.3
 */
public class MessageHandlerRegistrar {

    private final Supplier<Configuration> configurationSupplier;
    private final Function<Configuration, Object> messageHandlerBuilder;
    private final BiFunction<Configuration, Object, Registration> messageHandlerSubscriber;

    private Registration handlerRegistration;

    /**
     * Instantiate a {@link MessageHandlerRegistrar}, using the provided {@code configSupplier} to supply the {@link
     * Configuration} needed to build and register the message handler. For the latter operations the given {@code
     * messageHandlerBuilder} and {@code messageHandlerSubscriber} will be used respectively.
     *
     * @param configSupplier           a {@link Supplier} of the {@link Configuration} to be used by the given {@code
     *                                 messageHandlerBuilder} and {@code messageHandlerSubscriber}
     * @param messageHandlerBuilder    a {@link Function} using the {@code configSupplier}'s input to create a message
     *                                 handler
     * @param messageHandlerSubscriber a {@link BiFunction} using the the {@code configSupplier} and {@code
     *                                 messageHandlerBuilder} their output to register the created message handler with
     *                                 the {@link Configuration}
     */
    public MessageHandlerRegistrar(Supplier<Configuration> configSupplier,
                                   Function<Configuration, Object> messageHandlerBuilder,
                                   BiFunction<Configuration, Object, Registration> messageHandlerSubscriber) {
        this.configurationSupplier = configSupplier;
        this.messageHandlerBuilder = messageHandlerBuilder;
        this.messageHandlerSubscriber = messageHandlerSubscriber;
        this.handlerRegistration = null;
    }

    /**
     * Start the message handler registration process by building the message handler in the {@link
     * Phase#LOCAL_MESSAGE_HANDLER_REGISTRATIONS} phase. The specified {@code messageHandlerBuilder} is used for
     * creation and registration is performed through the {@code messageHandlerSubscriber}.
     */
    @StartHandler(phase = Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS)
    public void start() {
        Configuration config = configurationSupplier.get();
        Object annotatedHandler = messageHandlerBuilder.apply(config);
        assertNonNull(annotatedHandler, "AnnotatedMessageHandler may not be null");
        this.handlerRegistration = messageHandlerSubscriber.apply(config, annotatedHandler);
    }

    /**
     * Close the message handler registration initialized in phase {@link Phase#LOCAL_MESSAGE_HANDLER_REGISTRATIONS}
     * through the {@link #start()} method.
     */
    @ShutdownHandler(phase = Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS)
    public void shutdown() {
        handlerRegistration.cancel();
    }
}
