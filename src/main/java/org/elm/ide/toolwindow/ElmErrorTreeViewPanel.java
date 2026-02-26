package org.elm.ide.toolwindow;

import com.intellij.ide.errorTreeView.NewErrorTreeViewPanel;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.TreeSelectionListener;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public abstract class ElmErrorTreeViewPanel extends NewErrorTreeViewPanel {
    public final List<String> messages = new ArrayList<>();

    protected ElmErrorTreeViewPanel(Project project, @Nullable String helpId, boolean createExitAction, boolean createToolbar) {
        super(project, helpId, createExitAction, createToolbar);
        connectFriendlyMessages(project);
    }

    public void addErrorMessage(int type, String[] text, @Nullable VirtualFile file, int line, int column, String html) {
        super.addMessage(type, text, file, line, column, null);
        messages.add(html);
    }

    private void addSelectionListener(TreeSelectionListener listener) {
        myTree.addTreeSelectionListener(listener);
    }

    private void connectFriendlyMessages(Project project) {
        addSelectionListener(new ErrorTreeSelectionListener(project, messages));
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Friendly Messages");
        if (toolWindow == null) return;

        Object component = toolWindow.getContentManager().getContents().length > 0
            ? toolWindow.getContentManager().getContents()[0].getComponent()
            : null;
        if (!(component instanceof ReportPanel reportPanel)) return;

        Color bg = getBackground();
        reportPanel.getReportUI().setBackground(bg);
        reportPanel.getReportUI().setText("");
    }

    @Override
    protected void fillRightToolbarGroup(DefaultActionGroup group) {
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
        messages.clear();
    }

    @Override
    protected boolean canHideWarnings() {
        return false;
    }
}
