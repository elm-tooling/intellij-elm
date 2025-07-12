package org.elm.workspace

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ElmAutoDiscoverProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        asyncAutoDiscoverWorkspace(project)
    }
}
