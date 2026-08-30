#!/usr/bin/env python3
"""Logout is a terminal state for UI, transport, service, and notification."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"


def code(path):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def section(text, start, end):
    body = text[text.index(start):]
    return body[:body.index(end, len(start))]


class LogoutLifecycleContractTest(unittest.TestCase):
    def setUp(self):
        self.menu = code(JAVA / "MenuActivity.kt")
        self.main = code(JAVA / "MainActivity.kt")
        self.service = code(JAVA / "PTTService.kt")
        self.socket = code(JAVA / "WebSocketManager.kt")

    def test_successful_logout_replaces_the_whole_task_with_login(self):
        body = section(self.menu, "private fun performLogout()", "\n    }")
        self.assertIn("Intent(this, LoginActivity::class.java)", body)
        self.assertIn("Intent.FLAG_ACTIVITY_NEW_TASK", body)
        self.assertIn("Intent.FLAG_ACTIVITY_CLEAR_TASK", body)
        self.assertIn("startActivity", body)
        self.assertLess(body.index("WebSocketManager.logout()"), body.index("startActivity"))

    def test_main_screen_refuses_a_task_restored_without_a_session(self):
        created = section(self.main, "override fun onCreate", "\n    }")
        self.assertIn("WebSocketManager.init(applicationContext)", created)
        self.assertIn("WebSocketManager.hasAuthorizedSession()", created)
        self.assertIn("LoginActivity::class.java", created)

    def test_service_removes_its_ongoing_notification_when_it_ends(self):
        destroyed = section(self.service, "override fun onDestroy()", "\n    }")
        self.assertIn("stopForeground", destroyed)
        self.assertIn("NOTIFICATION_ID", destroyed)
        self.assertRegex(destroyed, r"NotificationManager[\s\S]{0,200}?cancel\(NOTIFICATION_ID\)")

    def test_logged_out_service_cannot_publish_reconnect_or_restart_sticky(self):
        started = section(self.service, "override fun onStartCommand", "\n    }")
        self.assertIn("WebSocketManager.hasAuthorizedSession()", started)
        self.assertIn("START_NOT_STICKY", started)
        self.assertLess(started.index("hasAuthorizedSession"), started.index("startForeground"))

    def test_force_logout_stops_the_service_before_opening_login(self):
        forced = section(self.service, "private fun handleForceLogout()", "\n    }")
        self.assertIn("stopForeground", forced)
        self.assertIn("stopSelf()", forced)
        self.assertLess(forced.index("stopSelf()"), forced.index("startActivity"))

    def test_socket_exposes_authorized_state_without_exposing_credentials(self):
        self.assertRegex(self.socket, r"fun hasAuthorizedSession\(\): Boolean\s*=\s*isAuthorizedSession")


if __name__ == "__main__":
    unittest.main()
