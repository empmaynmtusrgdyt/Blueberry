"""Тесты логики watchdog. Запуск: python -m unittest server/test_watchdog.py (из корня репозитория)."""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
import watchdog  # noqa: E402


def make(active: bool, percent=14, on_battery=True, level=0, speed=100, state=None):
    wd = watchdog.Watchdog({"WD_BATTERY_MIN_PERCENT": "15", "WD_IDLE_MINUTES": "20"})
    wd.active = lambda: active
    wd.battery = lambda: (percent, on_battery)
    wd.thermal = lambda: (level, speed)
    wd.model_state = lambda: state
    wd.emergency_stop = mock.Mock()
    wd.idle_unload = mock.Mock()
    return wd


class WatchdogTest(unittest.TestCase):
    def setUp(self):
        p = mock.patch.object(watchdog, "TRIPPED_FILE", Path("/nonexistent/tripped"))
        p.start(); self.addCleanup(p.stop)

    def test_низкий_заряд_при_работающем_сервере_останавливает(self):
        wd = make(active=True)
        wd.evaluate(act=True)
        wd.emergency_stop.assert_called_once()

    def test_низкий_заряд_при_выключенном_сервере_ничего_не_делает(self):
        wd = make(active=False)
        report = wd.evaluate(act=True)
        wd.emergency_stop.assert_not_called()
        self.assertIn("пропущены", report[0])

    def test_заряд_выше_порога_или_зарядка_не_останавливает(self):
        make(active=True, percent=50).evaluate(act=True)
        wd = make(active=True, on_battery=False)
        wd.evaluate(act=True)
        wd.emergency_stop.assert_not_called()

    def test_перегрев_дольше_порога_останавливает_только_при_работе(self):
        wd = make(active=True, percent=80, level=2)
        wd.hot_since = 1.0  # перегрев держится «давно»
        wd.evaluate(act=True)
        wd.emergency_stop.assert_called_once()
        wd = make(active=False, percent=80, level=2)
        wd.hot_since = 1.0  # перегрев держится «давно»
        wd.evaluate(act=True)
        wd.emergency_stop.assert_not_called()
        self.assertIsNone(wd.hot_since)

    def test_простой_выгружает_модель(self):
        wd = make(active=True, percent=80, state="IDLE")
        wd.started = 0.0  # последняя активность давно
        wd.evaluate(act=True)
        wd.idle_unload.assert_called_once()


if __name__ == "__main__":
    unittest.main()
