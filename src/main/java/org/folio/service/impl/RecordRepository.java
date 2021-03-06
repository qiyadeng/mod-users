package org.folio.service.impl;

import java.util.List;

import io.vertx.core.Future;

import org.folio.rest.jaxrs.model.CustomField;
import org.folio.rest.jaxrs.model.CustomFieldStatistic;
import org.folio.rest.jaxrs.model.User;

public interface RecordRepository {

  Future<CustomFieldStatistic> retrieveStatisticForField(CustomField field, String tenantId);

  Future<List<User>> findUsersByField(CustomField field, String tenantId);

  Future<Boolean> updateUser(User user, String tenantId);
}
