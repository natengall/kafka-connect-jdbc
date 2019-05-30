/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.dialect;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import io.confluent.connect.jdbc.dialect.DatabaseDialect.ColumnConverter;
import io.confluent.connect.jdbc.dialect.DatabaseDialectProvider.SubprotocolBasedProvider;
import io.confluent.connect.jdbc.sink.JdbcSinkConfig;
import io.confluent.connect.jdbc.sink.metadata.SinkRecordField;
import io.confluent.connect.jdbc.source.ColumnMapping;
import io.confluent.connect.jdbc.source.JdbcSourceConnectorConfig;
import io.confluent.connect.jdbc.util.ColumnDefinition;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.DateTimeUtils;
import io.confluent.connect.jdbc.util.ExpressionBuilder;
import io.confluent.connect.jdbc.util.IdentifierRules;
import io.confluent.connect.jdbc.util.TableId;
import io.confluent.connect.jdbc.util.ColumnDefinition.Mutability;
import io.confluent.connect.jdbc.util.ColumnDefinition.Nullability;

/**
 * A {@link DatabaseDialect} for SQL Server.
 */
public class SqlServerDatabaseDialect extends GenericDatabaseDialect {
  /**
   * The provider for {@link SqlServerDatabaseDialect}.
   */
  public static class Provider extends SubprotocolBasedProvider {
    public Provider() {
      super(SqlServerDatabaseDialect.class.getSimpleName(), "microsoft:sqlserver", "sqlserver",
            "jtds:sqlserver");
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new SqlServerDatabaseDialect(config);
    }
  }

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public SqlServerDatabaseDialect(AbstractConfig config) {
    super(config, new IdentifierRules(".", "[", "]"));
  }

  @Override
  protected boolean useCatalog() {
    // SQL Server uses JDBC's catalog to represent the database,
    // and JDBC's schema to represent the owner (e.g., "dbo")
    return true;
  }

  @Override
  protected String getSqlType(SinkRecordField field) {
    if (field.schemaName() != null) {
      switch (field.schemaName()) {
        case Decimal.LOGICAL_NAME:
          return "decimal(38," + field.schemaParameters().get(Decimal.SCALE_FIELD) + ")";
        case Date.LOGICAL_NAME:
          return "date";
        case Time.LOGICAL_NAME:
          return "time";
        case Timestamp.LOGICAL_NAME:
          return "datetime2";
        default:
          // pass through to normal types
      }
    }
    switch (field.schemaType()) {
      case INT8:
        return "tinyint";
      case INT16:
        return "smallint";
      case INT32:
        return "int";
      case INT64:
        return "bigint";
      case FLOAT32:
        return "real";
      case FLOAT64:
        return "float";
      case BOOLEAN:
        return "bit";
      case STRING:
        return "varchar(max)";
      case BYTES:
        return "varbinary(max)";
      default:
        return super.getSqlType(field);
    }
  }

  @Override
  public String buildDropTableStatement(
      TableId table,
      DropOptions options
  ) {
    ExpressionBuilder builder = expressionBuilder();

    if (options.ifExists()) {
      builder.append("IF OBJECT_ID('");
      builder.append(table);
      builder.append(", 'U') IS NOT NULL");
    }
    // SQL Server 2016 supports IF EXISTS
    builder.append("DROP TABLE ");
    builder.append(table);
    if (options.cascade()) {
      builder.append(" CASCADE");
    }
    return builder.toString();
  }

  @Override
  public List<String> buildAlterTable(
      TableId table,
      Collection<SinkRecordField> fields
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("ALTER TABLE ");
    builder.append(table);
    builder.append(" ADD");
    writeColumnsSpec(builder, fields);
    return Collections.singletonList(builder.toString());
  }

  @Override
  public String buildUpsertQueryStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("merge into ");
    builder.append(table);
    builder.append(" with (HOLDLOCK) AS target using (select ");
    builder.appendList()
           .delimitedBy(", ")
           .transformedBy(ExpressionBuilder.columnNamesWithPrefix("? AS "))
           .of(keyColumns, nonKeyColumns);
    builder.append(") AS incoming on (");
    builder.appendList()
           .delimitedBy(" and ")
           .transformedBy(this::transformAs)
           .of(keyColumns);
    builder.append(")");
    if (nonKeyColumns != null && !nonKeyColumns.isEmpty()) {
      builder.append(" when matched then update set ");
      builder.appendList()
             .delimitedBy(",")
             .transformedBy(this::transformUpdate)
             .of(nonKeyColumns);
    }
    builder.append(" when not matched then insert (");
    builder.appendList()
           .delimitedBy(", ")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(nonKeyColumns, keyColumns);
    builder.append(") values (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNamesWithPrefix("incoming."))
           .of(nonKeyColumns, keyColumns);
    builder.append(");");
    return builder.toString();
  }

  @Override
  public String buildDeleteStatement(
      TableId table,
      Collection<ColumnId> keyColumns
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("DELETE FROM ");
    builder.append(table);
    builder.append(" WHERE ");
    builder.appendList()
           .delimitedBy(" and ")
           .transformedBy(this::transformAs)
           .of(keyColumns);
    builder.append(")");
    return builder.toString();
  }
  
  @Override
  public String addFieldToSchema(
      ColumnDefinition columnDefn,
      SchemaBuilder builder
  ) {
    // Handle any SQL Server specific data types first, before adding the generic data types
    final String fieldName = fieldNameFor(columnDefn);
    switch (columnDefn.type()) {
      // Timestamp is a date + time
      case microsoft.sql.Types.DATETIMEOFFSET: {
          boolean optional = columnDefn.isOptional();
          SchemaBuilder tsSchemaBuilder = org.apache.kafka.connect.data.Timestamp.builder();
          if (optional) {
            tsSchemaBuilder.optional();
          }
          builder.field(fieldName, tsSchemaBuilder.build());
          return fieldName;
        }
      default:
    	  // Delegate for the remaining logic
    	  return super.addFieldToSchema(columnDefn, builder);
    }


  }

  @Override
  public ColumnConverter createColumnConverter(
      ColumnMapping mapping
  ) {
	// Handle any SQL Server specific data types first, before mapping the generic data types
    ColumnDefinition columnDefn = mapping.columnDefn();
    int col = mapping.columnNumber();
    TimeZone timeZone;
    if (config instanceof JdbcSourceConnectorConfig) {
        timeZone = ((JdbcSourceConnectorConfig) config).timeZone();
      } else if (config instanceof JdbcSinkConfig) {
        timeZone = ((JdbcSinkConfig) config).timeZone;
      } else {
        timeZone = TimeZone.getTimeZone(ZoneOffset.UTC);
      }
    switch (columnDefn.type()) {
      // Timestamp is a date + time
      case microsoft.sql.Types.DATETIMEOFFSET: {
        return rs -> rs.getTimestamp(col, DateTimeUtils.getTimeZoneCalendar(timeZone));
      }

      default:
    	// Delegate for the remaining logic
    	return super.createColumnConverter(mapping);
    }


  }
  
  @Override
  protected ColumnDefinition columnDefinition(
      ResultSet resultSet,
      ColumnId id,
      int jdbcType,
      String typeName,
      String classNameForType,
      Nullability nullability,
      Mutability mutability,
      int precision,
      int scale,
      Boolean signedNumbers,
      Integer displaySize,
      Boolean autoIncremented,
      Boolean caseSensitive,
      Boolean searchable,
      Boolean currency,
      Boolean isPrimaryKey
  ) {
    try {
      String isAutoIncremented = resultSet.getString(22);

      if ("yes".equalsIgnoreCase(isAutoIncremented)) {
        autoIncremented = Boolean.TRUE;
      } else if ("no".equalsIgnoreCase(isAutoIncremented)) {
        autoIncremented = Boolean.FALSE;
      }
    } catch (SQLException e) {
      log.warn("Unable to get auto incrementing column information", e);
    }

    return super.columnDefinition(
      resultSet,
      id,
      jdbcType,
      typeName,
      classNameForType,
      nullability,
      mutability,
      precision,
      scale,
      signedNumbers,
      displaySize,
      autoIncremented,
      caseSensitive,
      searchable,
      currency,
      isPrimaryKey
    );
  }

  private void transformAs(ExpressionBuilder builder, ColumnId col) {
    builder.append("target.")
           .appendColumnName(col.name())
           .append("=incoming.")
           .appendColumnName(col.name());
  }

  private void transformUpdate(ExpressionBuilder builder, ColumnId col) {
    builder.appendColumnName(col.name())
           .append("=incoming.")
           .appendColumnName(col.name());
  }

  @Override
  protected String sanitizedUrl(String url) {
    // SQL Server has semicolon delimited property name-value pairs, and several properties
    // that contain secrets
    return super.sanitizedUrl(url)
                .replaceAll("(?i)(;password=)[^;]*", "$1****")
                .replaceAll("(?i)(;keyStoreSecret=)[^;]*", "$1****")
                .replaceAll("(?i)(;gsscredential=)[^;]*", "$1****");
  }
}