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

package org.axonframework.springboot.autoconfig;

import org.axonframework.springboot.util.RegisterDefaultEntities;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.springboot.util.jpa.ContainerManagedEntityManagerProvider;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.jpa.JpaSagaStore;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.jpa.JpaTokenStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.jpa.JpaEventStorageEngine;
import org.axonframework.serialization.Serializer;
import org.axonframework.spring.config.AxonConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.persistence.EntityManagerFactory;

@ConditionalOnBean(EntityManagerFactory.class)
@RegisterDefaultEntities(packages = {
        "org.axonframework.eventsourcing.eventstore.jpa",
        "org.axonframework.eventhandling.tokenstore",
        "org.axonframework.modelling.saga.repository.jpa"
})
@Configuration
public class JpaAutoConfiguration {

    @ConditionalOnMissingBean({EventStorageEngine.class, EventStore.class})
    @Bean
    public EventStorageEngine eventStorageEngine(Serializer defaultSerializer,
                                                 PersistenceExceptionResolver persistenceExceptionResolver,
                                                 @Qualifier("eventSerializer") Serializer eventSerializer,
                                                 AxonConfiguration configuration,
                                                 EntityManagerProvider entityManagerProvider,
                                                 TransactionManager transactionManager) {
        return JpaEventStorageEngine.builder()
                                    .snapshotSerializer(defaultSerializer)
                                    .upcasterChain(configuration.upcasterChain())
                                    .persistenceExceptionResolver(persistenceExceptionResolver)
                                    .eventSerializer(eventSerializer)
                                    .entityManagerProvider(entityManagerProvider)
                                    .transactionManager(transactionManager)
                                    .build();
    }

    @ConditionalOnMissingBean
    @Bean
    public EntityManagerProvider entityManagerProvider() {
        return new ContainerManagedEntityManagerProvider();
    }

    @ConditionalOnMissingBean
    @Bean
    public TokenStore tokenStore(Serializer serializer, EntityManagerProvider entityManagerProvider) {
        return JpaTokenStore.builder()
                            .entityManagerProvider(entityManagerProvider)
                            .serializer(serializer)
                            .build();
    }

    @ConditionalOnMissingBean(SagaStore.class)
    @Bean
    public JpaSagaStore sagaStore(Serializer serializer, EntityManagerProvider entityManagerProvider) {
        return JpaSagaStore.builder()
                           .entityManagerProvider(entityManagerProvider)
                           .serializer(serializer)
                           .build();
    }
}
