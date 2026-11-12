# CI details - 8 distinct Kotlin compile errors, 0 failing tests

## All Kotlin compile errors (sorted, de-duplicated)
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:174:22 Unresolved reference 'rememberSaveable'.
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:175:18 Unresolved reference 'rememberSaveable'.
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:210:33 Unresolved reference 'not' for operator '!'.
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:211:25 Unresolved reference 'not' for operator '!'.
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:289:43 Unresolved reference 'it'.
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:291:33 @Composable invocations can only happen from the context of a @Composable function
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:291:38 @Composable invocations can only happen from the context of a @Composable function
- app/src/main/kotlin/app/autohost/ui/AppNavHost.kt:32:33 Unresolved reference 'rememberSaveable'.

## All failing tests

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':app:compileCorePyDebugKotlin'.
    > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
       > Compilation error. See log for more details
### 2
    Execution failed for task ':app:compileCoreLiteDebugKotlin'.
    > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
       > Compilation error. See log for more details
