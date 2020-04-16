/*
 * Copyright (c) 2010-2020. Axon Framework
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

package org.axonframework.integrationtests.polymorphic;

import org.axonframework.eventhandling.EventBus;
import org.axonframework.modelling.command.GenericJpaRepository;
import org.axonframework.modelling.command.Repository;
import org.axonframework.modelling.command.RepositoryProvider;
import org.axonframework.modelling.command.inspection.AggregateModel;

import java.util.HashMap;
import java.util.Map;
import javax.persistence.EntityManager;

import static org.mockito.Mockito.*;

/**
 * Tests JPA aggregate polymorphism.
 *
 * @author Milan Savic
 */
public class PolymorphicJpaAggregateAnnotationCommandHandlerTest
        extends AbstractPolymorphicAggregateAnnotationCommandHandlerTestSuite {

    private static final Map<Class<?>, Repository<?>> repositories = new HashMap<>();

    @Override
    public <T> Repository<T> repository(Class<T> aggregateType,
                                        AggregateModel<T> model,
                                        EntityManager entityManager) {
        GenericJpaRepository<T> repository = GenericJpaRepository
                .builder(aggregateType)
                .entityManagerProvider(() -> entityManager)
                .repositoryProvider(new RepositoryProvider() {
                    @Override
                    public <R> Repository<R> repositoryFor(Class<R> aggregateType) {
                        return (Repository<R>) repositories.get(aggregateType);
                    }
                })
                .eventBus(mock(EventBus.class))
                .aggregateModel(model)
                .build();
        repositories.put(aggregateType, repository);
        return repository;
    }
}
