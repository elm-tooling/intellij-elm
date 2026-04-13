package org.elm.ide.toolwindow;

import com.intellij.ide.errorTreeView.NewErrorTreeViewPanel;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ElmErrorTreeViewPanel extends NewErrorTreeViewPanel {
    protected ElmErrorTreeViewPanel(Project project, @Nullable String helpId, boolean createExitAction, boolean createToolbar) {
        super(project, helpId, createExitAction, createToolbar);
    }

    public void addErrorMessage(int type, String[] text, @Nullable VirtualFile file, int line, int column) {
        super.addMessage(type, text, file, line, column, null);
    }

    @Override
    protected void fillRightToolbarGroup(@NotNull DefaultActionGroup group) {
        AnAction rerunAction = getRerunAction();
        if (rerunAction != null) {
            group.addSeparator();
            group.add(rerunAction);
        }
    }

    @Nullable
    public AnAction getRerunAction() {
        return null;
    }

    public void clearMessages() {
        getErrorViewStructure().clear();
    }

    @Override
    protected boolean canHideWarnings() {
        return false;
    }
}
