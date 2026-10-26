"""autohost_ref: executable reference implementations of host logic (test oracles).

The Android host re-implements this logic in Kotlin. The golden vectors in testing/golden/ are shared: the Kotlin
implementation must produce exactly the results these Python implementations produce. These modules are never
shipped inside the app.
"""
