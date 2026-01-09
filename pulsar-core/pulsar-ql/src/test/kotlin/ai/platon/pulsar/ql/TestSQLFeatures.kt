package ai.platon.pulsar.ql

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.ql.context.AbstractSQLContext
import kotlin.test.Test

/**
 * Index tests.
 */
class TestSQLFeatures : TestBase() {

    @Test
    fun testStringConcat() {
        // There different behaviour in different DBs
        execute("SELECT NULL||'A'")
    }

    @Test
    fun testExplode() {
        execute("SELECT * FROM explode(make_array('A','B','C'))")
        execute("SELECT * FROM explode(make_array())")
        execute("SELECT * FROM explode()")
        execute("SELECT * FROM posexplode(make_array('A','B','C'))")
    }

    @Test
    fun testArray() {
        execute("SET @r=make_array(1, 2, 3)")
        query("SELECT @r")

        execute("SET @r=make_array('a', 'b', 1 + 3)")
        query("SELECT @r")

        query("SELECT LENGTH(@r)")
        query("SELECT ARRAY_LENGTH(@r)")
    }

    @Test
    fun testMap() {
        execute("SET @r=map('a', 1, 'b', 2, 'c', 3)")
        query("SELECT map('a', 1, 'b', 2, 'c', 3)")
        query("SELECT to_json(map('a', 1, 'b', 2, 'c', 3))")

//        assertResultSetEquals("""(('a', 1), ('b', 2), ('c', 3))""",
//            "SELECT map('a', 1, 'b', 2, 'c', 3)")
//        assertResultSetEquals("""{"a":"1","b":"2","c":"3"}""",
//            "SELECT to_json(map('a', 1, 'b', 2, 'c', 3))")
    }

    @Test
    fun testGroupConcat() {
        execute("drop TABLE if exists test")
        execute("CREATE TABLE test(id INT, name VARCHAR)")
        execute(("INSERT INTO test VALUES (2, 'a'), (1, 'a'), (3, 'b');" + " INSERT INTO test VALUES (4, 'e'), (5, 'f'), (10, 'g')"))
        query("SELECT GROUP_CONCAT(name) FROM test")
    }

    @Test
    fun testGroupInline() {
        execute("drop TABLE if exists test")
        execute("CREATE TABLE test(id INT, name VARCHAR)")
        execute(("INSERT INTO test VALUES (2, 'a'), (1, 'a'), (3, 'b');" + " INSERT INTO test VALUES (4, 'e'), (5, 'f'), (10, 'g')"))
        execute("SET @R=(SELECT GROUP_COLLECT(name) FROM test)")
        query("SELECT * FROM POSEXPLODE(@R)")
    }

    @Test
    fun testVariable() {
        execute("drop TABLE if exists test")
        execute("CREATE TABLE test(number INT, name VARCHAR)")
        execute(("INSERT INTO test VALUES (2, 'a'), (1, 'a'), (3, 'b');" + " INSERT INTO test VALUES (4, 'e'), (5, 'f'), (10, 'g')"))
        execute("SET @r = (SELECT * FROM test LIMIT 1)")
        query("SELECT @r")
    }

    @Test
    fun testMultipleStatement() {
        val sql = "SELECT A, B, C" +
                " FROM TABLE(" +
                "     A INT=(1, 2, 3, 4, 5, 6)," +
                "     B VARCHAR=('apple', 'orange', 'banana', 'bluebarry', 'guava', 'lemon')," +
                "     C DECIMAL=(5.2, 4.9, 2.3, 3.5, 4.1, 4.6)" +
                " ) t;" +
                " SELECT a, b FROM table(a INT=(7, 8, 9), b VARCHAR=('John', 'Lucy', 'Vince')) WHERE b LIKE '%u%'" +
                ""

        val connection = (context as AbstractSQLContext).randomConnection
        val state = connection.createStatement()
        val rs = state.executeQuery(sql)

        while (rs.next()) {
            print(rs.getString(1) + " " + rs.getString(2))
            printlnPro()
        }

        if (state.moreResults) {
            printlnPro("moreResults")
            while (rs.next()) {
                print(rs.getString(1) + " " + rs.getString(2))
                printlnPro()
            }
        }

        rs.close()
        state.close()
    }

    @Test
    fun testSpecialCharsInIdentifier() {
        // stat.execute("set enable_external=sigma");
        execute("create table `http://item.jd.com/19283721.html`(h1 varchar, `#jd-price` decimal)")
        execute("CREATE INDEX ON `http://item.jd.com/19283721.html`(h1)")
        execute("CREATE INDEX ON `http://item.jd.com/19283721.html`(`#jd-price`)")
        execute(
                "INSERT INTO `http://item.jd.com/19283721.html`" +
                        " VALUES('iphone', '6575'), ('lephone', '3998'), ('huawei phone', '2889');" +
                        " INSERT INTO `http://item.jd.com/19283721.html` " +
                        " VALUES('e', 200.1), ('f', 99.8), ('old huawei phone', 1000)")
        val rs = query(
                ("SELECT h1, `#jd-price` AS price" +
                        " FROM `http://item.jd.com/19283721.html`" +
                        " WHERE h1 LIKE '%phone%' AND `#jd-price` > 2000 ORDER BY `#jd-price` DESC")
        )
        // assertEquals("iphone", rs.getString(1))
    }

    @Test
    fun testSimpleIndex() {
        execute("drop TABLE if exists test")
        execute("CREATE TABLE test(number INT, name VARCHAR)")
        execute(("INSERT INTO test VALUES (2, 'a'), (1, 'a'), (3, 'b');" + " INSERT INTO test VALUES (4, 'e'), (5, 'f'), (10, 'g')"))
        execute("CREATE INDEX ON test(name)")

        execute("CREATE TABLE test2(number INT, name VARCHAR)")
        execute(("INSERT INTO test2 VALUES (12, 'ax'), (11, 'az'), (13, 'ay');" + " INSERT INTO test2 VALUES (14, 'bx'), (25, 'by'), (100, 'bz')"))
        execute("CREATE INDEX ON test2(name)")

        query("SELECT number, name AS n FROM test WHERE name LIKE '%a%' ORDER BY number;")
        query("SELECT x/3 AS a, count(*) c FROM system_range(1, 10) GROUP BY a HAVING c>2")
    }

    @Test
    fun testBuiltInFunctionTable() {
        query(("SELECT a,b FROM table(a INT=(1, 2, 3, 4), b CHAR=('x', 'y', 'w', 'z')) " + "WHERE a>0 AND b IN ('x', 'y')"))
    }
}
