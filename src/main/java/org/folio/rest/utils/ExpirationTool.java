package org.folio.rest.utils;


import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.BiFunction;

import static org.folio.rest.impl.UsersAPI.TABLE_NAME_USERS;
import org.folio.cql2pgjson.CQL2PgJSON;
import org.folio.rest.jaxrs.model.User;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;

public final class ExpirationTool {
  private static final Logger logger = LoggerFactory.getLogger(ExpirationTool.class);
  /** PostgresClient::getInstance, or some other method for unit testing */
  static BiFunction<Vertx, String, PostgresClient> postgresClient = PostgresClient::getInstance;

  private ExpirationTool() {
    throw new UnsupportedOperationException("Cannot instantiate utility class.");
  }

  public static void doExpiration(Vertx vertx, Context context) {
    logger.info("Calling doExpiration()");
    context.runOnContext(v -> {
      //Get a list of tenants
      PostgresClient pgClient = PostgresClient.getInstance(vertx);
      String tenantQuery = "select nspname from pg_catalog.pg_namespace where nspname LIKE '%_mod_users';";
      pgClient.select(tenantQuery, reply -> {
        if(reply.succeeded()) {
          List<JsonObject> obList = reply.result().getRows();
          for(JsonObject ob : obList) {
            String nsTenant = ob.getString("nspname");
            String suffix = "_mod_users";
            int suffixLength = nsTenant.length() - suffix.length();
            final String tenant = nsTenant.substring(0, suffixLength);
            logger.info("Calling doExpirationForTenant for tenant " + tenant);
            Future<Integer> expireFuture = doExpirationForTenant(vertx, context, tenant);
            expireFuture.setHandler(res -> {
              if(res.failed()) {
                logger.info(String.format("Attempt to expire records for tenant %s failed: %s",
                        tenant, res.cause().getLocalizedMessage()));
              } else {
                logger.info(String.format("Expired %s users", res.result()));
              }
            });
          }
        } else {
          logger.info(String.format("TenantQuery '%s' failed: %s", tenantQuery,
                  reply.cause().getLocalizedMessage()));
        }
      });
    });
  }

  public static Future<Integer> doExpirationForTenant(Vertx vertx, Context context, String tenant) {
    Promise<Integer> promise = Promise.promise();
    try {
      String nowDateString =  new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS\'Z\'").format(new Date());
      String query = String.format("active == true AND expirationDate < %s", nowDateString);
      CQL2PgJSON cql2pgJson = new CQL2PgJSON(Arrays.asList(TABLE_NAME_USERS+".jsonb"));
      CQLWrapper cqlWrapper = new CQLWrapper(cql2pgJson, query);
      String[] fieldList = {"*"};
      PostgresClient pgClient = postgresClient.apply(vertx, tenant);
      pgClient.get(TABLE_NAME_USERS, User.class, fieldList, cqlWrapper, true, false, reply -> {
        if (reply.failed()) {
          logger.info(String.format("Error executing postgres query: '%s', %s",
            query, reply.cause().getLocalizedMessage()));
          promise.fail(reply.cause());
          return;
        }
        if (reply.result().getResults().isEmpty()) {
          logger.info(String.format("No results found for query %s", query));
          promise.complete(0);
          return;
        }
        List<User> userList = reply.result().getResults();
        List<Future> futureList = new ArrayList<>();
        for(User user : userList) {
          user.setActive(Boolean.FALSE);
          Future<Void> saveFuture = saveUser(vertx, tenant, user);
          futureList.add(saveFuture);
        }
        CompositeFuture compositeFuture = CompositeFuture.join(futureList);
        compositeFuture.setHandler(compRes -> {
          int succeededCount = 0;
          for(Future fut : futureList) {
            if(fut.succeeded()) {
              succeededCount++;
            }
          }
          promise.complete(succeededCount);
        });
      });
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      promise.fail(e);
    }
    return promise.future();
  }

  static Future<Void> saveUser(Vertx vertx, String tenant, User user) {
    logger.info(String.format("Updating user with id %s", user.getId()));
    Promise<Void> promise = Promise.promise();
    try {
      PostgresClient pgClient = postgresClient.apply(vertx, tenant);
      pgClient.update(TABLE_NAME_USERS, user, user.getId(), updateReply -> {
        if (updateReply.succeeded()) {
          promise.complete();
          return;
        }
        logger.info(String.format("Error updating user %s: %s", user.getId(),
          updateReply.cause().getLocalizedMessage()));
        promise.fail(updateReply.cause());
      });
    } catch(Exception e) {
      promise.tryFail(e);
    }
    return promise.future();
  }

}
