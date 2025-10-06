package net.chrisrichardson.ftgo.common;

import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.function.StandardSQLFunction;
import org.hibernate.type.StandardBasicTypes;

import java.sql.Types;

/**
 * Hibernate dialect for Google Cloud Spanner
 * Extends the base Dialect class to provide Spanner-specific SQL features
 */
public class SpannerDialect extends Dialect {

    public SpannerDialect() {
        super();

        // Register column types for Spanner
        registerColumnType(Types.BIT, "BOOL");
        registerColumnType(Types.BOOLEAN, "BOOL");
        registerColumnType(Types.TINYINT, "INT64");
        registerColumnType(Types.SMALLINT, "INT64");
        registerColumnType(Types.INTEGER, "INT64");
        registerColumnType(Types.BIGINT, "INT64");
        registerColumnType(Types.FLOAT, "FLOAT64");
        registerColumnType(Types.DOUBLE, "FLOAT64");
        registerColumnType(Types.NUMERIC, "NUMERIC");
        registerColumnType(Types.DECIMAL, "NUMERIC");
        registerColumnType(Types.CHAR, "STRING(1)");
        registerColumnType(Types.VARCHAR, "STRING(MAX)");
        registerColumnType(Types.LONGVARCHAR, "STRING(MAX)");
        registerColumnType(Types.CLOB, "STRING(MAX)");
        registerColumnType(Types.NCHAR, "STRING(1)");
        registerColumnType(Types.NVARCHAR, "STRING(MAX)");
        registerColumnType(Types.LONGNVARCHAR, "STRING(MAX)");
        registerColumnType(Types.NCLOB, "STRING(MAX)");
        registerColumnType(Types.DATE, "DATE");
        registerColumnType(Types.TIME, "TIMESTAMP");
        registerColumnType(Types.TIMESTAMP, "TIMESTAMP");
        registerColumnType(Types.BINARY, "BYTES(MAX)");
        registerColumnType(Types.VARBINARY, "BYTES(MAX)");
        registerColumnType(Types.LONGVARBINARY, "BYTES(MAX)");
        registerColumnType(Types.BLOB, "BYTES(MAX)");

        // Register VARCHAR with length
        registerColumnType(Types.VARCHAR, 255, "STRING($l)");
        registerColumnType(Types.NVARCHAR, 255, "STRING($l)");

        // Register common SQL functions
        registerFunction("concat", new StandardSQLFunction("CONCAT", StandardBasicTypes.STRING));
        registerFunction("substring", new StandardSQLFunction("SUBSTR", StandardBasicTypes.STRING));
        registerFunction("length", new StandardSQLFunction("LENGTH", StandardBasicTypes.INTEGER));
        registerFunction("upper", new StandardSQLFunction("UPPER", StandardBasicTypes.STRING));
        registerFunction("lower", new StandardSQLFunction("LOWER", StandardBasicTypes.STRING));
        registerFunction("trim", new StandardSQLFunction("TRIM", StandardBasicTypes.STRING));
        registerFunction("current_date", new StandardSQLFunction("CURRENT_DATE", StandardBasicTypes.DATE));
        registerFunction("current_timestamp", new StandardSQLFunction("CURRENT_TIMESTAMP", StandardBasicTypes.TIMESTAMP));
    }

    @Override
    public boolean supportsIdentityColumns() {
        return false;
    }

    @Override
    public boolean supportsSequences() {
        return true;
    }

    @Override
    public String getSequenceNextValString(String sequenceName) {
        return "SELECT GET_NEXT_SEQUENCE_VALUE(SEQUENCE " + sequenceName + ")";
    }

    @Override
    public boolean supportsLimit() {
        return true;
    }

    @Override
    public String getLimitString(String sql, boolean hasOffset) {
        return sql + (hasOffset ? " limit ? offset ?" : " limit ?");
    }

    @Override
    public boolean bindLimitParametersInReverseOrder() {
        return true;
    }

    @Override
    public boolean supportsCurrentTimestampSelection() {
        return true;
    }

    @Override
    public String getCurrentTimestampSelectString() {
        return "SELECT CURRENT_TIMESTAMP()";
    }

    @Override
    public boolean isCurrentTimestampSelectStringCallable() {
        return false;
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
    }

    @Override
    public boolean hasAlterTable() {
        return true;
    }

    @Override
    public boolean dropConstraints() {
        return false;
    }

    @Override
    public String getAddColumnString() {
        return "ADD COLUMN";
    }

    @Override
    public boolean supportsIfExistsBeforeTableName() {
        return true;
    }

    @Override
    public boolean supportsIfExistsAfterTableName() {
        return false;
    }

    @Override
    public String getCascadeConstraintsString() {
        return "";
    }

    @Override
    public boolean supportsCascadeDelete() {
        return true;
    }

    @Override
    public char closeQuote() {
        return '`';
    }

    @Override
    public char openQuote() {
        return '`';
    }

    @Override
    public boolean canCreateSchema() {
        return false;
    }

    @Override
    public String[] getCreateSchemaCommand(String schemaName) {
        return new String[0];
    }

    @Override
    public String[] getDropSchemaCommand(String schemaName) {
        return new String[0];
    }

    @Override
    public boolean supportsCommentOn() {
        return false;
    }

    @Override
    public String getTableComment(String comment) {
        return "";
    }

    @Override
    public String getColumnComment(String comment) {
        return "";
    }
}
