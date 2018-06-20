package io.eventuate.examples.tram.ordersandcustomers.snapshots;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.hibernate.Session;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQL82Dialect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DBLockService {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  @Transactional
  public <T> T withLockedTables(LockSpecification lockSpecification, Supplier<T> callback) {
    try {
      jdbcTemplate.execute(createLockQueryDependingOnDatabase(lockSpecification));
      return callback.get();
    } finally {
      executeUnlockQueryIfNecessary();
    }
  }

  public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  private boolean isMysql() {
    return getDialect() instanceof MySQLDialect;
  }

  private boolean isPostgres() {
    return getDialect() instanceof PostgreSQL82Dialect;
  }

  private boolean isDatabaseSupported() {
    return isMysql() || isPostgres();
  }

  Dialect getDialect() {
    Session session = (Session) entityManager.getDelegate();
    try {
      return (Dialect)PropertyUtils.getProperty(session.getSessionFactory(), "dialect");
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  String createLockQueryDependingOnDatabase(LockSpecification lockSpecification) {
    if (isMysql()) {
      return buildMySqlLockQuery(lockSpecification);
    } else if (isPostgres()) {
      return buildPostgresLockQuery(lockSpecification);
    } else throw new DatabaseIsNotSupportedException();
  }

  void executeUnlockQueryIfNecessary() {
    if (isMysql()) {
      unlockMysqlTables();
    }
  }

  void unlockMysqlTables() {
    jdbcTemplate.execute("unlock tables;");
  }

  String buildMySqlLockQuery(LockSpecification lockSpecification) {
    Map<TableSpec, LockType> tables = new LinkedHashMap<>();
    tables.put(lockSpecification.mainTable, lockSpecification.getMainTableLockType());
    tables.putAll(lockSpecification.additionalLocks);

    String tableSpecificationPart = tables
            .entrySet()
            .stream()
            .map(e -> String.format("%s%s %s",
                    e.getKey().getName(),
                    e.getKey().getSynonym().map(" "::concat).orElse(""),
                    e.getValue().name().toLowerCase()))
            .collect(Collectors.joining(", "));

    return String.format("lock tables %s;", tableSpecificationPart);
  }

  String buildPostgresLockQuery(LockSpecification lockSpecification) {
    return String.format("lock table %s in EXCLUSIVE mode;", lockSpecification.mainTable.getName());
  }

  public static class LockSpecification {
    private TableSpec mainTable;
    private LockType mainTableLockType;
    private Map<TableSpec, LockType> additionalLocks;

    public LockSpecification(TableSpec mainTable, LockType mainTableLockType, Map<TableSpec, LockType> additionalLocks) {
      this.mainTable = mainTable;
      this.mainTableLockType = mainTableLockType;
      this.additionalLocks = additionalLocks;
    }

    public LockSpecification(TableSpec mainTable, LockType mainTableLockType) {
      this.mainTable = mainTable;
      this.mainTableLockType = mainTableLockType;
      this.additionalLocks = Collections.emptyMap();
    }

    public TableSpec getMainTable() {
      return mainTable;
    }

    public LockType getMainTableLockType() {
      return mainTableLockType;
    }

    public Map<TableSpec, LockType> getAdditionalLocks() {
      return additionalLocks;
    }
  }

  public enum LockType {
    READ, WRITE
  }

  public static class TableSpec {
    private String name;
    private Optional<String> synonym;

    public TableSpec(String name, String synonym) {
      this.name = name;
      this.synonym = Optional.of(synonym);
    }

    public TableSpec(String name) {
      this.name = name;
      this.synonym = Optional.empty();
    }

    public String getName() {
      return name;
    }

    public Optional<String> getSynonym() {
      return synonym;
    }

    @Override
    public boolean equals(Object o) {
      return EqualsBuilder.reflectionEquals(this, o);
    }

    @Override
    public int hashCode() {
      return HashCodeBuilder.reflectionHashCode(this);
    }
  }
}
