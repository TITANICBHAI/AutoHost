# CI details - 0 distinct Kotlin compile errors, 0 failing tests

## All Kotlin compile errors (sorted, de-duplicated)

## All failing tests

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':app:testCorePyDebugUnitTest'.
    > Could not resolve all files for configuration ':app:corePyDebugUnitTestRuntimeClasspath'.
       > Failed to transform nativeruntime-dist-compat-1.0.18.jar (org.robolectric:nativeruntime-dist-compat:1.0.18) to match attributes {artifactType=android-classes-jar, org.gradle.category=library, org.gradle.libraryelements=jar, org.gradle.status=release, org.gradle.usage=java-runtime}.
          > Could not download nativeruntime-dist-compat-1.0.18.jar (org.robolectric:nativeruntime-dist-compat:1.0.18)
             > Could not get resource 'https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.18/nativeruntime-dist-compat-1.0.18.jar'.
                > Could not GET 'https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.18/nativeruntime-dist-compat-1.0.18.jar'. Received status code 429 from server: Too Many Requests
### 2
    Execution failed for task ':app:testCoreLiteDebugUnitTest'.
    > Could not resolve all files for configuration ':app:coreLiteDebugUnitTestRuntimeClasspath'.
       > Failed to transform nativeruntime-dist-compat-1.0.18.jar (org.robolectric:nativeruntime-dist-compat:1.0.18) to match attributes {artifactType=android-classes-jar, org.gradle.category=library, org.gradle.libraryelements=jar, org.gradle.status=release, org.gradle.usage=java-runtime}.
          > Could not download nativeruntime-dist-compat-1.0.18.jar (org.robolectric:nativeruntime-dist-compat:1.0.18)
             > Could not get resource 'https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.18/nativeruntime-dist-compat-1.0.18.jar'.
                > Could not GET 'https://repo.maven.apache.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.18/nativeruntime-dist-compat-1.0.18.jar'. Received status code 429 from server: Too Many Requests
