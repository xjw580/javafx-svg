"""Bounded checks for this repository's deliberately small JitPack definition."""
from pathlib import Path
import shlex
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

class JitPackConfigurationTest(unittest.TestCase):
    def test_exact_jdk_and_install_configuration(self):
        # Pin Java 21 and require successful selection in the same shell as Maven.
        lines = [line.strip() for line in (ROOT/'jitpack.yml').read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith('#')]
        self.assertEqual(lines, ['before_install:', '- sdk install java 21.0.13.0+9-tem',
                                 'install:', '- sdk use java 21.0.13.0+9-tem && mvn -B -V -Pheadless clean install'])
        command = shlex.split(lines[-1][2:])
        self.assertEqual(command[:5], ['sdk', 'use', 'java', '21.0.13.0+9-tem', '&&'])
        self.assertEqual(command[-2:], ['clean', 'install'])
        self.assertIn('-Pheadless', command)
        self.assertFalse(any('skip' in part.lower() for part in command))

    def test_rc_coordinates_do_not_masquerade_as_jitpack_coordinates(self):
        pom = ET.parse(ROOT/'pom.xml').getroot()
        self.assertEqual(pom.findtext('m:groupId', namespaces=NS), 'club.xiaojiawei')
        self.assertEqual(pom.findtext('m:artifactId', namespaces=NS), 'javafx-svg')
        self.assertEqual(pom.findtext('m:version', namespaces=NS), '1.0.0-rc')
        docs = (ROOT/'release/JITPACK.md').read_text()
        self.assertIn('<groupId>com.github.xjw580</groupId>', docs)
        self.assertIn('<version>v1.0.0-rc</version>', docs)

    def test_production_and_test_dependency_scopes(self):
        pom = ET.parse(ROOT/'pom.xml').getroot()
        dependencies = {d.findtext('m:artifactId', namespaces=NS):
                        d.findtext('m:scope', default='compile', namespaces=NS)
                        for d in pom.findall('m:dependencies/m:dependency', NS)}
        self.assertEqual({a for a,s in dependencies.items() if s=='compile'},
                         {'javafx-graphics', 'kotlin-stdlib'})
        for artifact in ('javafx-controls', 'javafx-fxml', 'junit-jupiter', 'kotlin-test-junit5'):
            self.assertEqual(dependencies[artifact], 'test')
        self.assertEqual(pom.findtext('m:properties/m:maven.compiler.release', namespaces=NS), '21')
        self.assertEqual(pom.findtext('m:properties/m:kotlin.compiler.jvmTarget', namespaces=NS), '21')

if __name__ == '__main__':
    unittest.main()
