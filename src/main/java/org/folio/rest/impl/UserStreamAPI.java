package org.folio.rest.impl;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Function;

import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.Address;
import org.folio.rest.jaxrs.model.AddressType;
import org.folio.rest.jaxrs.model.User;
import org.folio.rest.jaxrs.model.UserdataCollection;
import org.folio.rest.jaxrs.model.Usergroup;
import org.folio.rest.jaxrs.resource.UserStream;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.Criteria.Criteria;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.Criteria.Limit;
import org.folio.rest.persist.Criteria.Offset;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.persist.facets.FacetField;
import org.folio.rest.persist.facets.FacetManager;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.ResourceUtils;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.utils.ValidationHelper;
import org.z3950.zing.cql.CQLParseException;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSON;
import org.z3950.zing.cql.cql2pgjson.CQL2PgJSONException;
import org.z3950.zing.cql.cql2pgjson.FieldException;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

import org.folio.rest.jaxrs.model.UserStreamGetOrder;

@Path("userStream")
public class UserStreamAPI implements UserStream {

  public static final String TABLE_NAME_USERS = "users";
  public static final String VIEW_NAME_USER_GROUPS_JOIN = "users_groups_view";

  private final Messages messages = Messages.getInstance();
  public static final String USER_ID_FIELD = "'id'";
  public static final String USER_NAME_FIELD = "'username'";
  private static final String OKAPI_HEADER_TENANT = "x-okapi-tenant";
  private final Logger logger = LoggerFactory.getLogger(UsersAPI.class);
  public static final String RAML_PATH = "ramls";
  private static final String USER_SCHEMA_PATH = RAML_PATH + "/userdata.json";
  private static final String USER_SCHEMA = ResourceUtils.resource2String(USER_SCHEMA_PATH);
  private static final LinkedHashMap<String,String> fieldsAndSchemas = getFieldsAndSchemas();

  private static final ObjectMapper mapper = new ObjectMapper();

  private static LinkedHashMap<String,String> getFieldsAndSchemas() {
    LinkedHashMap<String,String> map = new LinkedHashMap<>();
    map.put(VIEW_NAME_USER_GROUPS_JOIN+".jsonb", USER_SCHEMA);
    map.put(VIEW_NAME_USER_GROUPS_JOIN+".group_jsonb", UserGroupAPI.GROUP_SCHEMA);
    return map;
  }

  public UserStreamAPI(Vertx vertx, String tenantId) {
    PostgresClient.getInstance(vertx, tenantId).setIdField("id");
  }

  /**
   * right now, just query the join view if a cql was passed in, otherwise work with the
   * master users table. this can be optimized in the future to check if there is really a need
   * to use the join view due to cross table cqling - like returning users sorted by group name
   * @param cql
   * @return
   */
  private String getTableName(String cql) {
    if(cql != null && cql.contains("patronGroup.")){
      return VIEW_NAME_USER_GROUPS_JOIN;
      //}
    }
    return TABLE_NAME_USERS;
  }

  /**
   * check for entries in the cql which reference the group table, indicating a join is needed
   * and update the cql accordingly - by replacing the patronGroup. prefix with g. which is what
   * the view refers to the groups table
   * @param cql
   * @return
   */
  private static String convertQuery(String cql){
    if(cql != null){
      return cql.replaceAll("(?i)patronGroup\\.", VIEW_NAME_USER_GROUPS_JOIN+".group_jsonb.");
    }
    return cql;
  }

  static CQLWrapper getCQL(String query, int limit, int offset) throws CQL2PgJSONException, IOException {
    if(query != null && query.contains("patronGroup.")) {
      query = convertQuery(query);
      CQL2PgJSON cql2pgJson = new CQL2PgJSON(fieldsAndSchemas);
      return new CQLWrapper(cql2pgJson, query).setLimit(new Limit(limit)).setOffset(new Offset(offset));
    } else {
      CQL2PgJSON cql2pgJson = new CQL2PgJSON(TABLE_NAME_USERS+".jsonb", USER_SCHEMA);
      return new CQLWrapper(cql2pgJson, query).setLimit(new Limit(limit)).setOffset(new Offset(offset));
    }
  }

  @Validate
  @Override
  public void getUserStream(
    String query, String orderBy, UserStreamGetOrder order, int offset, int limit,
    List<String> facets, String lang, RoutingContext routingContext, Map<String, String> okapiHeaders,
    Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext
  ) {

    logger.debug("Streaming users");
    try {
      CQLWrapper cql = getCQL(query,limit,offset);

      List<FacetField> facetList = FacetManager.convertFacetStrings2FacetFields(facets, "jsonb");

      String tenantId = TenantTool.calculateTenantId(okapiHeaders.get(OKAPI_HEADER_TENANT));
      String tableName = getTableName(query);
      String[] fieldList = {"*"};
      logger.debug("Headers present are: " + okapiHeaders.keySet().toString());
      logger.debug("tenantId = " + tenantId);

      HttpServerResponse response = routingContext.response();
      response.setChunked(true);

      PostgresClient.getInstance(vertxContext.owner(), tenantId)
        .streamGet(tableName, User.class, "*", cql.toString(), true, false, facetList, user -> {

        try {
          response.write(mapper.writeValueAsString(user), "UTF-8");
        } catch (Exception e) {
          logger.error(e.getMessage(), e);
        }

      }, reply -> {

        logger.debug("response: " + reply);

        response.end();
        response.close();

      });

    } catch(Exception e) {
      
    }
  }

}
