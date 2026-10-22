"""P0-03 smoke check: import Requests and report the interpreter process."""

import os
import platform

import requests


def run():
    return (
        f"Python: {platform.python_version()}\n"
        f"Requests: {requests.__version__}\n"
        f"python_pid={os.getpid()}"
    )
