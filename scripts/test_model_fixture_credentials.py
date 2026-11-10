"""Exercise the live evaluator's actual credential derivation without provider calls."""
from pathlib import Path
import runpy
import unittest


script = runpy.run_path(str(Path(__file__).with_name("test-campus-live-model.py")))


class ModelFixtureCredentialsTest(unittest.TestCase):
    def test_same_isolated_configuration_recreates_the_login_credential(self):
        derive = script["fixture_password"]
        settings = {"JWT_SECRET": "isolated-test-secret-" * 3}
        self.assertEqual(derive("test-customer", settings), derive("test-customer", settings))
        self.assertEqual(64, len(derive("test-customer", settings)))

    def test_credentials_are_bound_to_both_fixture_identity_and_private_configuration(self):
        derive = script["fixture_password"]
        settings = {"JWT_SECRET": "first-isolated-secret-" * 3}
        value = derive("test-customer", settings)
        self.assertNotEqual(value, derive("other-customer", settings))
        self.assertNotEqual(value, derive("test-customer", {"JWT_SECRET": "second-private-secret-" * 3}))

    def test_missing_configuration_fails_instead_of_creating_a_default_credential(self):
        with self.assertRaises(KeyError):
            script["fixture_password"]("test-customer", {})


if __name__ == "__main__":
    unittest.main()
