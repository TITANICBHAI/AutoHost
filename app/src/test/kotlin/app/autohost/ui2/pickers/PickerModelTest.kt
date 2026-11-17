package app.autohost.ui2.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickerModelTest {

    private val apps = listOf(
        AppChoice("com.example.zeta", "zeta Notes"),
        AppChoice("com.example.chrome", "Chrome"),
        AppChoice("com.example.photos.a", "Photos"),
        AppChoice("com.example.photos.b", "Photos"),
        AppChoice("com.example.chrome", "Chrome"),
        AppChoice("app.autohost", "AutoHost"),
    )

    private val tasks = listOf(
        TaskChoice("app.autohost.script.make-video", "Make video", "Python script"),
        TaskChoice("app.autohost.macro.post-1a2b", "Post it", "Macro"),
        TaskChoice("app.autohost.macro.post-1a2b", "Post it", "Macro"),
        TaskChoice("app.autohost.macro.alpha-9z9z", "alpha", "Macro"),
    )

    @Test fun `apps are sorted by name ignoring case with one row per package and our own app left out`() {
        val rows = PickerModel.apps(apps, "", hide = "app.autohost")
        assertEquals(listOf("Chrome", "Photos", "Photos", "zeta Notes"), rows.map { it.label })
        assertEquals(
            listOf("com.example.chrome", "com.example.photos.a", "com.example.photos.b", "com.example.zeta"),
            rows.map { it.pkg },
        )
        // the dialog keys its rows by package, so a package must never appear twice
        assertEquals(rows.size, rows.map { it.pkg }.toSet().size)
    }

    @Test fun `the app search matches the name only and ignores case and spaces around the words`() {
        assertEquals(listOf("com.example.photos.a", "com.example.photos.b"), PickerModel.apps(apps, "PHOTO").map { it.pkg })
        assertEquals(listOf("com.example.chrome"), PickerModel.apps(apps, "  chro ").map { it.pkg })
        // "example" is in every package name and in no app name: package names are never searched
        assertEquals(emptyList<AppChoice>(), PickerModel.apps(apps, "example"))
        assertEquals(5, PickerModel.apps(apps, "   ").size)
    }

    @Test fun `tasks are sorted by name with one row per id and the excluded task left out`() {
        assertEquals(listOf("alpha", "Make video", "Post it"), PickerModel.tasks(tasks, "").map { it.name })
        assertEquals(
            listOf("alpha", "Make video"),
            PickerModel.tasks(tasks, "", exclude = "app.autohost.macro.post-1a2b").map { it.name },
        )
    }

    @Test fun `the task search matches the name only`() {
        assertEquals(listOf("Make video"), PickerModel.tasks(tasks, "VIDEO").map { it.name })
        // "script" and "macro" are in ids and kinds, not in names
        assertEquals(emptyList<TaskChoice>(), PickerModel.tasks(tasks, "script"))
        assertEquals(emptyList<TaskChoice>(), PickerModel.tasks(tasks, "macro"))
    }

    @Test fun `the button shows a name or nothing but never the stored package or id`() {
        assertEquals("Chrome", PickerModel.appLabel(apps, "com.example.chrome"))
        assertNull(PickerModel.appLabel(apps, ""))
        assertNull(PickerModel.appLabel(apps, "  "))
        assertNull(PickerModel.appLabel(apps, "com.gone.app"))
        assertEquals("Make video", PickerModel.taskName(tasks, "app.autohost.script.make-video"))
        assertNull(PickerModel.taskName(tasks, ""))
        assertNull(PickerModel.taskName(tasks, "app.autohost.script.gone"))
    }

    @Test fun `a task is described by what kind it is in plain words`() {
        assertEquals("Macro", PickerModel.taskKind("app.autohost.macro.post-1a2b", "flow"))
        assertEquals("Python script", PickerModel.taskKind("app.autohost.script.make-video", "python"))
        assertEquals("JavaScript", PickerModel.taskKind("com.example.js", "javascript"))
        assertEquals("Flow", PickerModel.taskKind("com.example.flow", "flow"))
        assertNull(PickerModel.taskKind("com.example.installed", null))
    }
}
