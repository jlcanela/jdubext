package com.github.ornicar.jdubext

import java.sql.{ DriverManager, Connection, Types, PreparedStatement }
import java.util.Properties
import scala.collection.mutable.WeakHashMap
import com.codahale.jdub._
import scalaz.{ Validation, Validations }

object Database {

  def postgresql(
    host: String,
    dbName: String,
    username: String,
    password: String,
    jdbcProperties: Map[String, String] = Map.empty
  ) = connect(host, dbName, username, password, "postgresql", jdbcProperties)

  /**
   * Create a connection to the given database.
   */
  def connect(
    host: String,
    dbName: String,
    username: String,
    password: String,
    driverName: String = "postgresql",
    jdbcProperties: Map[String, String] = Map.empty
  ): Database = {

    Class forName "org.%s.Driver".format(driverName)

    val props = new Properties
    props.setProperty("user", username)
    props.setProperty("password", password)
    for ((k, v) <- jdbcProperties) props.setProperty(k, v)

    val url = "jdbc:%s://%s/%s".format(driverName, host, dbName)

    val connection = DriverManager.getConnection(url, props)

    new Database(connection)
  }
}

class Database(connection: Connection) extends Validations {

  /**
   * Opens a transaction which is committed after `f` is called.
   * If `f` throws an exception, the transaction is rolled back.
   */
  def transaction[A](f: Transaction => A): A = {
    val autoCommit = connection getAutoCommit()
    try {
      connection setAutoCommit false
      val result = f(new Transaction(connection))
      connection.commit()
      result
    } catch {
      case e => { connection.rollback(); throw e }
    } finally {
      if (autoCommit) connection setAutoCommit true
    }
  }

  /**
   * Opens a functional transaction which is committed after `f` is called.
   * If `f` returns a Left value, the transaction is rolled back.
   */
  def transactionEither[A, B](f: Transaction => Either[A, B]): Either[A, B] = {
    val autoCommit = connection getAutoCommit()
    connection setAutoCommit false
    val result = f(new Transaction(connection))
    result.fold( _ => connection.rollback(), _ => connection.commit())
    if (autoCommit) connection setAutoCommit true
    result
  }

  /**
   * Opens a functional transaction which is committed after `f` is called.
   * If `f` returns a Failure value, the transaction is rolled back.
   */
  def transactionValidation[A, B](f: Transaction => Validation[A, B]): Validation[A, B] =
    validation {
      transactionEither(((v: Validation[A, B]) ⇒ v.either) compose f)
    }

  /**
   * Performs a query and returns the results.
   */
  def apply[A](query: RawQuery[A]): A = {

    val stmt = prepareStatement(query.sql)
    prepare(stmt, query.values)
    val results = stmt.executeQuery()
    try {
      query.handle(results)
    } finally {
      results.close()
    }
  }

  /**
   * Performs a query and returns the results.
   */
  def query[A](query: RawQuery[A]): A = apply(query)

  /**
   * Executes an update, insert, delete, or DDL statement.
   */
  def execute(statement: Statement) = {

    val stmt = prepareStatement(statement.sql)
    prepare(stmt, statement.values)
    stmt.executeUpdate()
  }

  private val stmtCache = WeakHashMap[String, PreparedStatement]()

  private def prepareStatement(sql: String) = stmtCache.getOrElseUpdate(
    sql,
    connection.prepareStatement(sql))

  /**
   * Closes all connections to the database.
   */
  def close() {
    connection.close()
  }

  /**
   * Tells if the connection works
   */
  def isAlive: Boolean = try {
    query(PingQuery)
  } catch {
    case e => false
  }

  private[this] def prepare(stmt: PreparedStatement, values: Seq[Any]) {

    for ((value, index) <- values.zipWithIndex) {
      if (value == null) stmt.setNull(index + 1, Types.NULL)
      else stmt.setObject(index + 1, value.asInstanceOf[AnyRef])
    }
  }

  /**
   * A simple query which returns {@code true} if the server can process a simple
   * query ({@code SELECT 1}) which doesn't touch any tables or anything.
   */
  object PingQuery extends Query[Boolean] {
    val sql = "SELECT 1"
    val values = Nil
    def reduce(rows: Iterator[Row]) = rows.exists { _.int(0) == Some(1) }
  }
}
