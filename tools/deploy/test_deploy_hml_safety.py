#!/usr/bin/env python3

import re
import sys
import unittest
from pathlib import Path


REPO = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
sys.argv = [sys.argv[0]]


class HmlDeploySafetyTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.workflow = (REPO / '.github/workflows/deploy-homologacao.yml').read_text()
        cls.publisher = (REPO / 'tools/deploy/deploy-kersting-gps-hml.sh').read_text()
        cls.gate = (REPO / 'tools/deploy/kersting-gps-hml-ssh-gate.sh').read_text()
        cls.access = (REPO / 'tools/deploy/kersting-gps-hml-access.sh').read_text()
        cls.installer = (REPO / 'tools/deploy/install-deploy-hml-user.sh').read_text()

    def test_hml_destinations_are_exact(self):
        expected = (
            'readonly RUNTIME_ROOT="/opt/traccar-hml"',
            'readonly SERVICE_NAME="traccar-hml.service"',
            'readonly DATABASE_NAME="gps_kersting_hml"',
            'readonly DATABASE_PORT="3308"',
            'readonly INTERNAL_URL="http://127.0.0.1:18082/"',
            'readonly PUBLIC_URL="https://gps-hml.kersting.net.br/"',
        )
        for value in expected:
            self.assertIn(value, self.publisher)

    def test_production_publisher_is_never_called(self):
        combined = self.workflow + self.gate + self.installer
        self.assertNotRegex(combined, r'(?<!-hml)deploy-kersting-gps(?:\s|["\'])')
        self.assertNotIn('sudo -n /usr/local/sbin/deploy-kersting-gps ', combined)

    def test_workflow_uses_hml_specific_secrets(self):
        required = {
            'HML_VPS_HOST',
            'HML_VPS_PORT',
            'HML_VPS_USER',
            'HML_VPS_SSH_KEY',
            'HML_VPS_KNOWN_HOSTS',
        }
        names = set(re.findall(r'secrets\.([A-Z][A-Z0-9_]*)', self.workflow))
        self.assertEqual(required, names)
        self.assertIn('[[ "$HML_VPS_USER" == "deploy-hml" ]]', self.workflow)

    def test_workflow_is_manual_and_allows_geofence_branch(self):
        self.assertIn('workflow_dispatch:', self.workflow)
        self.assertNotRegex(self.workflow, r'(?m)^\s+(push|pull_request):')
        self.assertIn('codex/melhorar-associacao-geocercas', self.workflow)
        self.assertIn('codex/geofence-prod-clean', self.workflow)
        self.assertIn('environment: homologacao', self.workflow)

    def test_sudoers_has_no_wildcard_or_arguments(self):
        line = 'deploy-hml ALL=(root) NOPASSWD: /usr/local/sbin/deploy-kersting-gps-hml'
        self.assertIn(line, self.installer)
        sudoers_block = self.installer.split("<<'SUDOERS'", 1)[1].split('SUDOERS', 1)[0]
        self.assertNotIn('*', sudoers_block)
        self.assertNotRegex(sudoers_block, r'deploy-kersting-gps-hml\s+\S')

    def test_ssh_key_is_forced_and_restricted(self):
        self.assertIn('restrict,command=', self.installer)
        self.assertIn('SSH_ORIGINAL_COMMAND', self.gate)
        for command in ('status)', 'deploy)', 'cleanup)', 'upload\\ *)'):
            self.assertIn(command, self.gate)
        self.assertIn('comando não autorizado', self.gate)

    def test_account_toggle_uses_expiration_and_keeps_password_locked(self):
        self.assertIn('usermod --lock "$DEPLOY_USER"', self.access)
        self.assertIn('chage --expiredate -1 "$DEPLOY_USER"', self.access)
        self.assertIn('chage --expiredate 1 "$DEPLOY_USER"', self.access)
        self.assertNotIn('usermod --unlock', self.access)
        self.assertNotIn('passwd --unlock', self.access)

    def test_backups_manifest_and_atomic_release_are_present(self):
        for marker in (
            'runtime_backup=',
            'database_backup=',
            '.kersting-deploy-info',
            'mv -Tf "$RUNTIME_ROOT/.current.new" "$CURRENT_LINK"',
            'restore_hml()',
            'assert_production_untouched',
        ):
            self.assertIn(marker, self.publisher)

    def test_gt06_hml_port_is_owned_and_not_exposed(self):
        self.assertIn('readonly GT06_PORT="5601"', self.publisher)
        self.assertIn('ss -H -lntp "sport = :$GT06_PORT"', self.publisher)
        self.assertIn('pid=$hml_pid,', self.publisher)
        self.assertIn('firewall-cmd --list-all-zones', self.publisher)
        self.assertIn('porta candidata 15023', self.publisher)

    def test_api_hml_configuration_is_checked_without_printing_values(self):
        self.assertIn('readonly API_ENV_FILE="/etc/kersting-api-hml/app.env"', self.publisher)
        self.assertIn('validate_api_is_hml', self.publisher)
        self.assertNotIn('cat "$API_ENV_FILE"', self.publisher)

    def test_mutating_systemctl_targets_variable_bound_to_hml(self):
        self.assertNotRegex(self.publisher, r'systemctl\s+(?:start|stop|restart|reload)\s+traccar(?:\.service)?(?:\s|$)')
        mutating = re.findall(r'systemctl\s+(?:start|stop|restart|reload)\s+([^\n]+)', self.publisher)
        self.assertTrue(mutating)
        self.assertTrue(all('"$SERVICE_NAME"' in line for line in mutating))

    def test_sql_is_select_only_outside_backup_restore_tools(self):
        self.assertRegex(self.publisher, r'-e\s+"SELECT\s')
        self.assertNotRegex(
            self.publisher,
            r'(?i)-e\s+"(?:UPDATE|DELETE|INSERT|ALTER|DROP|CREATE)\b',
        )


if __name__ == '__main__':
    unittest.main(verbosity=2)
