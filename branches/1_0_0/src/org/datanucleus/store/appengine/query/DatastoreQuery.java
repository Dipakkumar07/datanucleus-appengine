/**********************************************************************
Copyright (c) 2009 Google Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
**********************************************************************/
package org.datanucleus.store.appengine.query;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import static com.google.appengine.api.datastore.FetchOptions.Builder.withLimit;
import static com.google.appengine.api.datastore.FetchOptions.Builder.withOffset;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.FetchPlan;
import org.datanucleus.ManagedConnection;
import org.datanucleus.ObjectManager;
import org.datanucleus.StateManager;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.EmbeddedMetaData;
import org.datanucleus.metadata.MetaDataManager;
import org.datanucleus.query.compiler.QueryCompilation;
import org.datanucleus.query.expression.DyadicExpression;
import org.datanucleus.query.expression.Expression;
import org.datanucleus.query.expression.JoinExpression;
import org.datanucleus.query.expression.Literal;
import org.datanucleus.query.expression.OrderExpression;
import org.datanucleus.query.expression.ParameterExpression;
import org.datanucleus.query.expression.PrimaryExpression;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.appengine.DatastoreFieldManager;
import org.datanucleus.store.appengine.DatastoreManager;
import org.datanucleus.store.appengine.DatastorePersistenceHandler;
import org.datanucleus.store.appengine.DatastoreTable;
import org.datanucleus.store.appengine.Utils;
import org.datanucleus.store.appengine.Utils.Function;
import org.datanucleus.store.mapped.IdentifierFactory;
import org.datanucleus.store.mapped.MappedStoreManager;
import org.datanucleus.store.mapped.mapping.JavaTypeMapping;
import org.datanucleus.store.mapped.mapping.PersistenceCapableMapping;
import org.datanucleus.store.query.AbstractJavaQuery;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A unified JDOQL/JPQL query implementation for Datastore.
 *
 * Datanucleus supports in-memory evaluation of queries, but
 * for now we have it disabled and are only allowing queries
 * that can be natively fulfilled by the app engine datastore.
 *
 * TODO(maxr): More logging
 * TODO(maxr): Localized logging
 * TODO(maxr): Localized exception messages.
 *
 * @author Erick Armbrust <earmbrust@google.com>
 * @author Max Ross <maxr@google.com>
 */
public class DatastoreQuery implements Serializable {

  // Exposed for testing
  static final Expression.Operator GROUP_BY_OP = new Expression.Operator(
      "GROUP BY", Integer.MAX_VALUE);

  // Exposed for testing
  static final Expression.Operator HAVING_OP = new Expression.Operator(
      "HAVING", Integer.MAX_VALUE);

  // Exposed for testing
  static final Expression.Operator JOIN_OP = new Expression.Operator(
      "JOIN", Integer.MAX_VALUE);

  static final Set<Expression.Operator> UNSUPPORTED_OPERATORS =
      Utils.newHashSet((Expression.Operator) Expression.OP_ADD,
          (Expression.Operator) Expression.OP_BETWEEN,
          (Expression.Operator) Expression.OP_COM,
          (Expression.Operator) Expression.OP_CONCAT,
          (Expression.Operator) Expression.OP_DIV,
          (Expression.Operator) Expression.OP_IS,
          (Expression.Operator) Expression.OP_ISNOT,
          (Expression.Operator) Expression.OP_LIKE,
          (Expression.Operator) Expression.OP_MOD,
          (Expression.Operator) Expression.OP_NEG,
          (Expression.Operator) Expression.OP_MUL,
          (Expression.Operator) Expression.OP_NOT,
          (Expression.Operator) Expression.OP_OR,
          (Expression.Operator) Expression.OP_SUB);

  private static final
  Map<Expression.Operator, Query.FilterOperator> DATANUCLEUS_OP_TO_APPENGINE_OP = buildNewOpMap();

  private static Map<Expression.Operator, Query.FilterOperator> buildNewOpMap() {
    Map<Expression.Operator, Query.FilterOperator> map =
        new HashMap<Expression.Operator, Query.FilterOperator>();
    map.put(Expression.OP_EQ, Query.FilterOperator.EQUAL);
    map.put(Expression.OP_GT, Query.FilterOperator.GREATER_THAN);
    map.put(Expression.OP_GTEQ, Query.FilterOperator.GREATER_THAN_OR_EQUAL);
    map.put(Expression.OP_LT, Query.FilterOperator.LESS_THAN);
    map.put(Expression.OP_LTEQ, Query.FilterOperator.LESS_THAN_OR_EQUAL);
    return map;
  }

  /**
   * The query that is generated by Datanucleus.
   */
  private final AbstractJavaQuery query;

  /**
   * The datastore query that we most recently executed.
   * This should only be used for testing.
   */
  private transient Query mostRecentDatastoreQuery;

  /**
   * Constructs a new Datastore query based on a Datanucleus query.
   *
   * @param query The Datanucleus query to be translated into a Datastore query.
   */
  public DatastoreQuery(AbstractJavaQuery query) {
    this.query = query;
  }

  /**
   * We'd like to return {@link Iterable} instead but
   * {@link javax.persistence.Query#getResultList()} returns {@link List}.
   *
   * @param localiser The localiser to use.
   * @param compilation The compiled query.
   * @param fromInclNo The index of the first result the user wants returned.
   * @param toExclNo The index of the last result the user wants returned.
   * @param parameters Parameter values for the query.
   *
   * @return The result of executing the query.
   */
  public List<?> performExecute(Localiser localiser, QueryCompilation compilation,
      long fromInclNo, long toExclNo, Map<String, ?> parameters) {

    validate(compilation);

    if (toExclNo == 0 ||
        (rangeValueIsSet(toExclNo)
            && rangeValueIsSet(fromInclNo)
            && (toExclNo - fromInclNo) <= 0)) {
      // short-circuit - no point in executing the query
      return Collections.emptyList();
    }
    final ObjectManager om = getObjectManager();
    long startTime = System.currentTimeMillis();
    if (NucleusLogger.QUERY.isDebugEnabled()) {
      NucleusLogger.QUERY.debug(localiser.msg("021046", "DATASTORE", query.getSingleStringQuery(), null));
    }
    DatastoreManager storeMgr = (DatastoreManager) om.getStoreManager();
    ManagedConnection mconn = storeMgr.getConnection(om);
    try {
      DatastoreService ds = (DatastoreService) mconn.getConnection();
      final ClassLoaderResolver clr = om.getClassLoaderResolver();
      final AbstractClassMetaData acmd =
          getMetaDataManager().getMetaDataForClass(query.getCandidateClass(), clr);
      if (acmd == null) {
        throw new NucleusUserException("No meta data for " + query.getCandidateClass().getName()
            + ".  Perhaps you need to run the enhancer on this class?");
      }
      String kind =
          getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
      mostRecentDatastoreQuery = new Query(kind);
      DatastoreTable table = storeMgr.getDatastoreClass(acmd.getFullClassName(), clr);
      addFilters(compilation, mostRecentDatastoreQuery, parameters, acmd, table);
      addSorts(compilation, mostRecentDatastoreQuery, acmd);
      Iterable<Entity> entities;
      FetchOptions opts = buildFetchOptions(fromInclNo, toExclNo);
      if (opts != null) {
        entities = ds.prepare(mostRecentDatastoreQuery).asIterable(opts);
      } else {
        entities = ds.prepare(mostRecentDatastoreQuery).asIterable();
      }
      if (NucleusLogger.QUERY.isDebugEnabled()) {
        NucleusLogger.QUERY.debug(localiser.msg("021074", "DATASTORE",
            "" + (System.currentTimeMillis() - startTime)));
      }

      Function<Entity, Object> entityToPojoFunc = new Function<Entity, Object>() {
        public Object apply(Entity entity) {
          return entityToPojo(entity, acmd, clr, (DatastoreManager) om.getStoreManager());
        }
      };
      return new StreamingQueryResult(query, entities, entityToPojoFunc);
    } finally {
      mconn.release();
    }
  }

  /**
   * Datanucleus provides {@link Long#MAX_VALUE} if the range value was not set
   * by the user.
   */
  private boolean rangeValueIsSet(long rangeVal) {
    return rangeVal != Long.MAX_VALUE;
  }

  /**
   * Build a FetchOptions instance using the provided params.
   * @return A FetchOptions instance built using the provided params,
   * or {@code null} if neither param is set.
   */
  FetchOptions buildFetchOptions(long fromInclNo, long toExclNo) {
    FetchOptions opts = null;
    Integer offset = null;
    if (fromInclNo != 0 && rangeValueIsSet(fromInclNo)) {
      // datastore api expects an int because we cap you at 1000 anyway.
      offset = (int) Math.min(Integer.MAX_VALUE, fromInclNo);
      opts = withOffset(offset);
    }
    if (rangeValueIsSet(toExclNo)) {
      // datastore api expects an int because we cap you at 1000 anyway.
      int intExclNo = (int) Math.min(Integer.MAX_VALUE, toExclNo);
      if (opts == null) {
        // When fromInclNo isn't specified, intExclNo (the index of the last
        // result to return) and limit are the same.
        opts = withLimit(intExclNo);
      } else {
        // When we have values for both fromInclNo and toExclNo
        // we can't take toExclNo as the limit for the query because
        // toExclNo is the index of the last result, not the max
        // results to return.  In this scenario the limit is the
        // index of the last result minus the offset.  For example, if
        // fromInclNo is 10 and toExclNo is 25, the limit for the query
        // is 15 because we want 15 results starting after the first 10.
        
        // We know that offset won't be null because opts is not null.
        opts.limit(intExclNo - offset);
      }
    }
    return opts;
  }

  private Object entityToPojo(final Entity entity, final AbstractClassMetaData acmd,
      final ClassLoaderResolver clr, final DatastoreManager storeMgr) {
    return entityToPojo(entity, acmd, clr, storeMgr, getObjectManager(), query.getIgnoreCache());
  }

  /**
   * Converts the provided entity to a pojo.
   *
   * @param entity The entity to convert
   * @param acmd The meta data for the pojo class
   * @param clr The classloader resolver
   * @param storeMgr The store manager
   * @param om The object manager
   * @param ignoreCache Whether or not the cache should be ignored when the
   * object manager attempts to find the pojo
   * @return The pojo that corresponds to the provided entity.
   */
  public static Object entityToPojo(final Entity entity, final AbstractClassMetaData acmd,
      final ClassLoaderResolver clr, final DatastoreManager storeMgr, ObjectManager om,
      boolean ignoreCache) {
    storeMgr.validateMetaDataForClass(acmd, clr);
    FieldValues fv = new FieldValues() {
      public void fetchFields(StateManager sm) {
        sm.replaceFields(
            acmd.getPKMemberPositions(), new DatastoreFieldManager(sm, storeMgr, entity));
      }
      public void fetchNonLoadedFields(StateManager sm) {
        sm.replaceNonLoadedFields(
            acmd.getPKMemberPositions(), new DatastoreFieldManager(sm, storeMgr, entity));
      }
      public FetchPlan getFetchPlanForLoading() {
        return null;
      }
    };
    Object pojo = om.findObjectUsingAID(clr.classForName(acmd.getFullClassName()), fv, ignoreCache, true);
    StateManager stateMgr = om.findStateManager(pojo);
    DatastorePersistenceHandler handler = storeMgr.getPersistenceHandler();
    // TODO(maxr): Seems like we should be able to refactor the handler
    // so that we can do a fetch without having to hide the entity in the
    // state manager.
    handler.setAssociatedEntity(stateMgr, handler.getCurrentTransaction(om), entity);
    storeMgr.getPersistenceHandler().fetchObject(stateMgr, acmd.getAllMemberPositions());
    return pojo;
  }

  private void validate(QueryCompilation compilation) {
    if (query.getCandidateClass() == null) {
      throw new NucleusUserException(
          "Candidate class could not be found: " + query.getSingleStringQuery());
    }
    // We don't support in-memory query fulfillment, so if the query contains
    // a grouping or a having it's automatically an error.
    if (query.getGrouping() != null) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          GROUP_BY_OP);
    }

    if (query.getHaving() != null) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          HAVING_OP);
    }

    if (compilation.getExprFrom() != null) {
      for (Expression fromExpr : compilation.getExprFrom()) {
        checkNotJoin(fromExpr);
      }
    }
    // TODO(maxr): Add checks for subqueries and anything else we don't
    // allow.
  }

  private void checkNotJoin(Expression expr) {
    if (expr instanceof JoinExpression) {
      throw new UnsupportedDatastoreFeatureException("Cannot fulfill queries with joins.",
          query.getSingleStringQuery());
    }
    if (expr.getLeft() != null) {
      checkNotJoin(expr.getLeft());
    }
    if (expr.getRight() != null) {
      checkNotJoin(expr.getRight());
    }
  }

  /**
   * Adds sorts to the given {@link Query} by examining the compiled order
   * expression.
   */
  private void addSorts(QueryCompilation compilation, Query q, AbstractClassMetaData acmd) {
    Expression[] orderBys = compilation.getExprOrdering();
    if (orderBys == null) {
      return;
    }
    for (Expression expr : orderBys) {
      OrderExpression oe = (OrderExpression) expr;
      Query.SortDirection dir = oe.getSortOrder() == null || oe.getSortOrder().equals("ascending")
              ? Query.SortDirection.ASCENDING : Query.SortDirection.DESCENDING;
      PrimaryExpression left = (PrimaryExpression) oe.getLeft();
      AbstractMemberMetaData ammd = getMemberMetaData(acmd, left);
      if (ammd == null) {
        throw noMetaDataException(left.getId(), acmd.getFullClassName());
      }
      if (isParentPK(ammd)) {
        throw new UnsupportedDatastoreFeatureException(
            "Cannot sort by parent.", query.getSingleStringQuery());
      } else {
        String sortProp;
        if (ammd.isPrimaryKey()) {
          sortProp = Entity.KEY_RESERVED_PROPERTY;
        } else {
          sortProp = determinePropertyName(ammd);
        }
        q.addSort(sortProp, dir);
      }
    }
  }

  IdentifierFactory getIdentifierFactory() {
    return ((MappedStoreManager)getObjectManager().getStoreManager()).getIdentifierFactory();
  }

  /**
   * Adds filters to the given {@link Query} by examining the compiled filter
   * expression.
   */
  private void addFilters(QueryCompilation compilation, Query q, Map parameters,
      AbstractClassMetaData acmd, DatastoreTable table) {
    Expression filter = compilation.getExprFilter();
    QueryData qd = new QueryData(q, parameters, acmd, table);
    addExpression(filter, qd);
  }

  /**
   * Struct used to represent info about the query we need to fulfill.
   */
  private static final class QueryData {
    private final Query query;
    private final Map parameters;
    private final AbstractClassMetaData acmd;
    private final Map<String, DatastoreTable> tableMap = Utils.newHashMap();

    private QueryData(Query query, Map parameters, AbstractClassMetaData acmd, DatastoreTable table) {
      this.query = query;
      this.parameters = parameters;
      this.acmd = acmd;
      this.tableMap.put(acmd.getFullClassName(), table);
    }
  }

  /**
   * Recursively walks the given expression, adding filters to the given
   * {@link Query} where appropriate.
   *
   * @throws UnsupportedDatastoreOperatorException If we encounter an operator
   *           that we don't support.
   * @throws UnsupportedDatastoreFeatureException If the query uses a feature
   *           that we don't support.
   */
  private void addExpression(Expression expr, QueryData qd) {
    if (expr == null) {
      return;
    }
    checkForUnsupportedOperator(expr.getOperator());
    if (expr instanceof DyadicExpression) {
      if (expr.getOperator().equals(Expression.OP_AND)) {
        addExpression(expr.getLeft(), qd);
        addExpression(expr.getRight(), qd);
      } else if (DATANUCLEUS_OP_TO_APPENGINE_OP.get(expr.getOperator()) == null) {
        throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
            expr.getOperator());
      } else if (expr.getLeft() instanceof PrimaryExpression) {
        addLeftPrimaryExpression((PrimaryExpression) expr.getLeft(), expr
            .getOperator(), expr.getRight(), qd);
      } else {
        // Recurse!
        addExpression(expr.getLeft(), qd);
        addExpression(expr.getRight(), qd);
      }
    } else if (expr instanceof PrimaryExpression) {
      // Recurse!
      addExpression(expr.getLeft(), qd);
      addExpression(expr.getRight(), qd);
    } else {
      throw new UnsupportedDatastoreFeatureException(
          "Unexpected expression type while parsing query: "
              + expr.getClass().getName(), query.getSingleStringQuery());
    }
  }

  private void addLeftPrimaryExpression(PrimaryExpression left,
      Expression.Operator operator, Expression right, QueryData qd) {
    Query.FilterOperator op = DATANUCLEUS_OP_TO_APPENGINE_OP.get(operator);
    if (op == null) {
      throw new UnsupportedDatastoreFeatureException("Operator " + operator + " does not have a "
          + "corresponding operator in the datastore api.", query.getSingleStringQuery());
    }
    Object value;
    if (right instanceof PrimaryExpression) {
      value = qd.parameters.get(((PrimaryExpression) right).getId());
    } else if (right instanceof Literal) {
      value = ((Literal) right).getLiteral();
    } else if (right instanceof ParameterExpression) {
      value = right.getSymbol().getValue();
    } else if (right instanceof DyadicExpression) {
      // In general we don't support nested dyadic expressions
      // but we special case negation:
      // select * from table where val = -33
      DyadicExpression dyadic = (DyadicExpression) right;
      if (dyadic.getLeft() instanceof Literal &&
          ((Literal) dyadic.getLeft()).getLiteral() instanceof Number &&
          dyadic.getRight() == null &&
          Expression.OP_NEG.equals(dyadic.getOperator())) {
        Number negateMe = (Number) ((Literal) dyadic.getLeft()).getLiteral();
        value = negateNumber(negateMe);
      } else {
        throw new UnsupportedDatastoreFeatureException(
            "Right side of expression is composed of unsupported components.  "
            + "Left: " + dyadic.getLeft().getClass().getName()
            + ", Op: " + dyadic.getOperator()
            + ", Right: " + dyadic.getRight(), query.getSingleStringQuery());
      }
    } else {
      throw new UnsupportedDatastoreFeatureException(
          "Right side of expression is of unexpected type: " + right.getClass().getName(),
          query.getSingleStringQuery());
    }
    AbstractMemberMetaData ammd = getMemberMetaData(qd.acmd, left);
    if (ammd == null) {
      throw noMetaDataException(left.getId(), qd.acmd.getFullClassName());
    }
    JavaTypeMapping mapping = getMappingForFieldWithName(left.getTuples(), qd);
    if (mapping instanceof PersistenceCapableMapping) {
      processPersistenceCapableMapping(qd, op, ammd, value);
    } else if (isParentPK(ammd)) {
      addParentFilter(op, qd, internalPkToKey(qd.acmd, value));
    } else {
      String datastorePropName;
      if (ammd.isPrimaryKey()) {
        datastorePropName = Entity.KEY_RESERVED_PROPERTY;
        value = internalPkToKey(qd.acmd, value);
      } else {
        datastorePropName = determinePropertyName(ammd);
      }
      qd.query.addFilter(datastorePropName, op, value);
    }
  }

  private NucleusUserException noMetaDataException(String member, String fullClassName) {
    return new NucleusUserException(
        "No meta-data for member named " + member + " on class " + fullClassName
            + ".  Are you sure you provided the correct member name?");
  }

  private Object negateNumber(Number negateMe) {
    if (negateMe instanceof BigDecimal) {
      // datastore doesn't support filtering by BigDecimal to convert to
      // double.
      return ((BigDecimal) negateMe).negate().doubleValue();
    } else if (negateMe instanceof Float) {
      return -((Float) negateMe);
    } else if (negateMe instanceof Double) {
      return -((Double) negateMe);
    }
    return -negateMe.longValue();
  }

  private JavaTypeMapping getMappingForFieldWithName(List<String> tuples, QueryData qd) {
    DatastoreManager storeMgr = (DatastoreManager) getObjectManager().getStoreManager();
    ClassLoaderResolver clr = getObjectManager().getClassLoaderResolver();
    AbstractClassMetaData acmd = qd.acmd;
    JavaTypeMapping mapping = null;
    // We might be looking for the mapping for a.b.c
    for (String tuple : tuples) {
      DatastoreTable table = qd.tableMap.get(acmd.getFullClassName());
      if (table == null) {
        table = storeMgr.getDatastoreClass(acmd.getFullClassName(), clr);
        qd.tableMap.put(acmd.getFullClassName(), table);
      }
      // deepest mapping we have so far
      mapping = table.getMemberMapping(tuple);
      // set the class meta data to the class of the type of the field of the
      // mapping so that we go one deeper if there are any more tuples
      acmd = getMetaDataManager().getMetaDataForClass(mapping.getMemberMetaData().getType(), clr);
    }
    return mapping;
  }

  private AbstractMemberMetaData getMemberMetaData(
      AbstractClassMetaData acmd, PrimaryExpression left) {
    List<String> tuples = left.getTuples();
    AbstractMemberMetaData ammd = acmd.getMetaDataForMember(tuples.get(0));
    if (tuples.size() == 1) {
      return ammd;
    }
    EmbeddedMetaData emd = ammd.getEmbeddedMetaData();
    // more than one tuple, so it must be embedded data
    for (String tuple : tuples.subList(1, tuples.size())) {
      if (ammd.getEmbeddedMetaData() == null) {
        throw new NucleusUserException(
            query.getSingleStringQuery() + ": Can only filter by properties of a sub-object if "
            + "the sub-object is embedded.");
      }
      ammd = findMemberMetaDataWithName(tuple, emd.getMemberMetaData());
    }
    return ammd;
  }

  private AbstractMemberMetaData findMemberMetaDataWithName(
      String name, AbstractMemberMetaData[] ammdList) {
    for (AbstractMemberMetaData embedded : ammdList) {
      if (embedded.getName().equals(name)) {
        return embedded;
      }
    }
    // Not ok, but caller knows what to do
    return null;
  }

  private void processPersistenceCapableMapping(QueryData qd, Query.FilterOperator op,
                                                AbstractMemberMetaData ammd, Object value) {
    Object keyOrString;
    if (value instanceof Key || value instanceof String) {
      // This is a bit odd, but just to be nice we let users
      // provide the id itself rather than the object containing the id.
      keyOrString = value;
    } else {
      ApiAdapter apiAdapter = getObjectManager().getApiAdapter();
      keyOrString =
          apiAdapter.getTargetKeyForSingleFieldIdentity(apiAdapter.getIdForObject(value));
      if (keyOrString == null) {
        throw new NucleusUserException(query.getSingleStringQuery()
                                   + ": Parameter value " + value + " does not have an id.");
      }
    }
    Key valueKey = internalPkToKey(qd.acmd, keyOrString);
    verifyRelatedKeyIsOfProperType(ammd, valueKey);
    if (!qd.tableMap.get(ammd.getAbstractClassMetaData().getFullClassName()).isParentKeyProvider(ammd)) {
      // Looks like a join.  If it can be satisfied with just a
      // get on the secondary table, fulfill it.
      if (op != Query.FilterOperator.EQUAL) {
        throw new UnsupportedDatastoreFeatureException(
            "Only the equals operator is supported on conditions involving the owning side of a "
            + "one-to-one.", query.getSingleStringQuery());        
      }
      if (valueKey.getParent() == null) {
        throw new NucleusUserException(query.getSingleStringQuery() + ": Key of parameter value does "
                                   + "not have a parent.");
      }

      // The field is the child side of an owned one to one.  We can just add
      // the parent key to the query as an equality filter on id.
      qd.query.addFilter(
          Entity.KEY_RESERVED_PROPERTY, Query.FilterOperator.EQUAL, valueKey.getParent());
    } else {
      addParentFilter(op, qd, valueKey);
    }
  }

  private void verifyRelatedKeyIsOfProperType(AbstractMemberMetaData ammd, Key key) {
    String keyKind = key.getKind();
    ClassLoaderResolver clr = getObjectManager().getClassLoaderResolver();
    AbstractClassMetaData acmd = getMetaDataManager().getMetaDataForClass(ammd.getType(), clr);
    String fieldKind =
        getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
    if (!keyKind.equals(fieldKind)) {
      throw new NucleusUserException(query.getSingleStringQuery() + ": Field "
                                 + ammd.getFullFieldName() + " maps to kind " + fieldKind + " but"
                                 + " parameter value contains Key of kind " + keyKind );
    }
    // If the key has a parent, we also need to also verify that the parent of
    // the key is the property kind
    if (key.getParent() != null) {
      String keyParentKind = key.getParent().getKind();
      String fieldOwnerKind = getIdentifierFactory().newDatastoreContainerIdentifier(
          ammd.getAbstractClassMetaData()).getIdentifierName();
      if (!keyParentKind.equals(fieldOwnerKind)) {
        throw new NucleusUserException(query.getSingleStringQuery() + ": Field "
                                   + ammd.getFullFieldName() + " is owned by a class that maps to "
                                   + "kind " + fieldOwnerKind + " but"
                                   + " parameter value contains Key with parent of kind "
                                   + keyParentKind );
      }
    }
  }

  private String determinePropertyName(AbstractMemberMetaData ammd) {
    if (ammd.getColumn() != null) {
      return ammd.getColumn();
    } else if (ammd.getColumnMetaData() != null && ammd.getColumnMetaData().length != 0) {
      return ammd.getColumnMetaData()[0].getName();
    } else {
      return getIdentifierFactory().newDatastoreFieldIdentifier(ammd.getName()).getIdentifierName();
    }
  }

  private Key internalPkToKey(AbstractClassMetaData acmd, Object internalPk) {
    Key key;
    if (internalPk instanceof String) {
      try {
        key = KeyFactory.stringToKey((String) internalPk);
      } catch (IllegalArgumentException iae) {
        String kind =
            getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
        key = KeyFactory.createKey(kind, (String) internalPk);
      }
    } else if (internalPk instanceof Long) {
      String kind =
          getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
      key = KeyFactory.createKey(kind, (Long) internalPk);
    } else {
      key = (Key) internalPk;
    }
    return key;
  }

  private void addParentFilter(Query.FilterOperator op, QueryData qd, Key key) {
    // We only support queries on parent if it is an equality filter.
    if (op != Query.FilterOperator.EQUAL) {
      throw new UnsupportedDatastoreFeatureException("Operator is of type " + op + " but the "
          + "datastore only supports parent queries using the equality operator.",
          query.getSingleStringQuery());
    }
    // value must be String or Key
    qd.query.setAncestor(key);
  }

  private void checkForUnsupportedOperator(Expression.Operator operator) {
    if (UNSUPPORTED_OPERATORS.contains(operator)) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          operator);
    }
  }

  private boolean isParentPK(AbstractMemberMetaData ammd) {
    return ammd.hasExtension(DatastoreManager.PARENT_PK);
  }

  // Exposed for tests
  Query getMostRecentDatastoreQuery() {
    return mostRecentDatastoreQuery;
  }

  private ObjectManager getObjectManager() {
    return query.getObjectManager();
  }

  private MetaDataManager getMetaDataManager() {
    return getObjectManager().getMetaDataManager();
  }

  // Specialization just exists to support tests
  static class UnsupportedDatastoreOperatorException extends
      UnsupportedOperationException {
    private final String queryString;
    private final Expression.Operator operator;

    UnsupportedDatastoreOperatorException(String queryString,
        Expression.Operator operator) {
      super(queryString);
      this.queryString = queryString;
      this.operator = operator;
    }

    @Override
    public String getMessage() {
      return "Problem with query <" + queryString
          + ">: App Engine datastore does not support operator " + operator;
    }

    public Expression.Operator getOperation() {
      return operator;
    }
  }

  // Specialization just exists to support tests
  static class UnsupportedDatastoreFeatureException extends
      UnsupportedOperationException {

    UnsupportedDatastoreFeatureException(String msg, String queryString) {
      super("Problem with query <" + queryString + ">: " + msg);
    }
  }
}