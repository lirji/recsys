"""用实际回归形态验证持久层边界检查不会被注释、文本块或XML片段绕过。"""
import unittest
from check_persistence_boundaries import R9_ADAPTER, java_findings, xml_findings


class PersistenceBoundaryTest(unittest.TestCase):
    def test_java_text_block_and_raw_jdbc_are_rejected(self):
        source = 'class Job { String sql = """\nSELECT id\nFROM item\n"""; void run() { c.prepareStatement(sql); } }'
        self.assertEqual(2, len(java_findings(source, "Job.java")))

    def test_comment_examples_and_jdbc_type_handlers_are_allowed(self):
        source = '// SELECT id FROM item\n/* new JdbcTemplate(ds) */\nimport java.sql.PreparedStatement;\nclass Handler { String v = "https://example.test"; }'
        self.assertEqual([], java_findings(source, "Handler.java"))

    def test_sql_annotations_and_template_regressions_are_rejected(self):
        source = '@Select("SELECT id FROM item") interface Mapper {} class Repo { JdbcTemplate jdbc; }'
        self.assertEqual(3, len(java_findings(source, "Repo.java")))

    def test_connection_adapter_exception_does_not_allow_sql(self):
        self.assertEqual([], java_findings("class Store { JdbcTemplate connections; }", R9_ADAPTER))
        self.assertTrue(java_findings('class Store { String sql="DELETE FROM item"; }', R9_ADAPTER))

    def test_xml_raw_interpolation_and_returning_in_fragments_are_rejected(self):
        source = '<mapper><sql id="write">UPDATE item SET category=#{category} RETURNING id</sql><select id="write"><include refid="write"/> ${table}</select></mapper>'
        self.assertEqual(2, len(xml_findings(source, "Mapper.xml")))

    def test_returning_flags_and_fixed_table_branches_are_allowed(self):
        source = '<mapper><select id="write" affectData="true" flushCache="true" useCache="false">UPDATE <choose><when test="owned">item</when><otherwise>item_local</otherwise></choose> SET category=#{category} RETURNING id</select></mapper>'
        self.assertEqual([], xml_findings(source, "Mapper.xml"))


if __name__ == "__main__":
    unittest.main()
