package io.stargate.it.cql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.config.TypedDriverOption;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.QueryTrace;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.ByteUtils;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.ProtocolError;
import com.google.common.collect.ImmutableMap;
import io.stargate.it.storage.ClusterConnectionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class SimpleStatementTest extends JavaDriverTestBase {

  private static final String KEY = "test";

  public SimpleStatementTest(ClusterConnectionInfo backend) {
    super(backend);
  }

  @Override
  protected void customizeConfig(OptionsMap config) {
    config.put(TypedDriverOption.REQUEST_PAGE_SIZE, 20);
  }

  @BeforeEach
  public void setupSchema() {
    // table where every column forms the primary key.
    session.execute("CREATE TABLE IF NOT EXISTS test (k text, v int, PRIMARY KEY(k, v))");
    for (int i = 0; i < 100; i++) {
      session.execute("INSERT INTO test (k, v) VALUES (?, ?)", KEY, i);
    }

    // table with simple primary key, single cell.
    session.execute("CREATE TABLE IF NOT EXISTS test2 (k text primary key, v int)");
  }

  @Test
  public void should_use_positional_values() {
    SimpleStatement statement = SimpleStatement.newInstance("SELECT v FROM test WHERE k=?", KEY);
    ResultSet resultSet = session.execute(statement);
    assertThat(resultSet).hasSize(100);
  }

  @Test
  public void should_allow_nulls_in_positional_values() {
    session.execute("INSERT into test2 (k, v) values (?, ?)", KEY, null);
    Row row = session.execute("select k,v from test2 where k=?", KEY).one();
    assertThat(row).isNotNull();
    assertThat(row.isNull("v")).isTrue();
  }

  @Test
  @Disabled(
      "C* 3.11 throws 'InvalidQueryException: Invalid amount of bind variables', Stargate doesn't throw")
  public void should_fail_when_too_many_positional_values_provided() {
    assertThatThrownBy(
            () -> session.execute("INSERT into test2 (k, v) values (?, ?)", KEY, 1, 2, 3))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  @Disabled(
      "C* 3.11 returns an InvalidQueryException, Stargate returns a ServerError(IndexOutOfBoundsException)")
  public void should_fail_when_not_enough_positional_values_provided() {
    // For SELECT queries, all values must be filled
    assertThatThrownBy(() -> session.execute("SELECT * from test where k = ? and v = ?", KEY))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  public void should_use_named_values() {
    SimpleStatement statement =
        SimpleStatement.newInstance("SELECT v FROM test WHERE k=:k", ImmutableMap.of("k", KEY));
    ResultSet resultSet = session.execute(statement);
    assertThat(resultSet).hasSize(100);
  }

  @Test
  public void should_allow_nulls_in_named_values() {
    session.execute(
        SimpleStatement.builder("INSERT into test2 (k, v) values (:k, :v)")
            .addNamedValue("k", KEY)
            .addNamedValue("v", null)
            .build());

    Row row = session.execute("select k,v from test2 where k=?", KEY).one();
    assertThat(row).isNotNull();
    assertThat(row.isNull("v")).isTrue();
  }

  @Test
  @Disabled(
      "C* 3.11 returns an InvalidQueryException, Stargate returns a ServerError(IndexOutOfBoundsException)")
  public void should_fail_when_named_value_missing() {
    // For SELECT queries, all values must be filled
    assertThatThrownBy(
            () ->
                session.execute(
                    SimpleStatement.newInstance(
                        "SELECT * from test where k = :k and v = :v", ImmutableMap.of("k", KEY))))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  public void should_use_paging_state() {
    SimpleStatement statement = SimpleStatement.newInstance("SELECT v FROM test WHERE k=?", KEY);
    ResultSet resultSet = session.execute(statement);
    assertThat(resultSet.getAvailableWithoutFetching()).isEqualTo(20);

    statement = statement.copy(resultSet.getExecutionInfo().getPagingState());
    resultSet = session.execute(statement);

    assertThat(resultSet.iterator().next().getInt("v")).isEqualTo(20);
  }

  @Test
  @Disabled("C* 3.11 returns a ProtocolError, Stargate returns a ServerError")
  public void should_throw_when_using_corrupt_paging_state() {
    SimpleStatement statement =
        SimpleStatement.builder("SELECT v FROM test WHERE k=?")
            .addPositionalValue(KEY)
            .setPagingState(ByteUtils.fromHexString("0x1234"))
            .build();
    assertThatThrownBy(() -> session.execute(statement)).isInstanceOf(ProtocolError.class);
  }

  @Test
  @Disabled("Looks like the query timestamp is not propagated correctly")
  public void should_use_query_timestamp() {
    long timestamp = 10; // whatever
    session.execute(
        SimpleStatement.builder("INSERT INTO test2 (k, v) values ('test', 1)")
            .setQueryTimestamp(timestamp)
            .build());

    Row row = session.execute("SELECT writetime(v) FROM test2 WHERE k = 'test'").one();
    assertThat(row).isNotNull();
    assertThat(row.getLong(0)).isEqualTo(timestamp);
  }

  @Test
  public void should_use_tracing() {
    SimpleStatement statement = SimpleStatement.newInstance("SELECT v FROM test WHERE k=?", KEY);

    ExecutionInfo executionInfo = session.execute(statement).getExecutionInfo();
    assertThat(executionInfo.getTracingId()).isNull();

    executionInfo = session.execute(statement.setTracing(true)).getExecutionInfo();
    assertThat(executionInfo.getTracingId()).isNotNull();
    QueryTrace queryTrace = executionInfo.getQueryTrace();
    assertThat(queryTrace).isNotNull();
    assertThat(queryTrace.getEvents()).isNotEmpty();
  }

  @Test
  public void should_use_page_size_on_statement() {
    SimpleStatement statement =
        SimpleStatement.newInstance("SELECT v FROM test WHERE k=?", KEY).setPageSize(10);
    ResultSet resultSet = session.execute(statement);
    assertThat(resultSet.getAvailableWithoutFetching()).isEqualTo(10);
  }
}