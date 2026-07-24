package org.elm.ide.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*
import org.elm.workspace.ElmPackageProject

/**
 * Find unused functions, parameters, etc.
 */
class ElmUnusedSymbolInspection : ElmLocalInspection() {

    override fun visitElement(element: ElmPsiElement, holder: ProblemsHolder, isOnTheFly: Boolean) {
        if (element !is ElmNameIdentifierOwner) return
        val project = element.project
        val scope = element.useScope
        val name = element.name

        // ignore certain kinds of declarations which we don't want to inspect
        if (element is ElmTypeAliasDeclaration ||
                element is ElmTypeDeclaration ||
                element is ElmTypeVariable ||
                element is ElmFieldType ||
                element is ElmLowerPattern && element.parent is ElmRecordPattern) {
            // TODO revisit: implementation for types is a little tricky since
            //      type annotations are optional; punting for now
            return
        }

        if (isProgramEntryPoint(element)) return
        if (isPackagePublicApi(element)) return
        if (isPhantomTypeConstructor(element)) return

        if (scope is GlobalSearchScope) {
            // to keep inspection/analysis time brief, bail out if 'Find Usages' will be slow
            val searchCost = PsiSearchHelper.getInstance(project).isCheapEnoughToSearch(name, scope, null)
            if (searchCost == TOO_MANY_OCCURRENCES) return
        }

        // perform Find Usages, bailing out on the first relevant usage we encounter
        var hasUsages = false
        ReferencesSearch.search(element).forEach(Processor { usage ->
            val isIgnoredUsage = usage.element is ElmTypeAnnotation || usage.element is ElmExposedItemTag
            if (!isIgnoredUsage) {
                hasUsages = true
                return@Processor false
            }
            true
        })

        if (!hasUsages) {
            markAsUnused(holder, element, name)
        }
    }

    private fun isProgramEntryPoint(element: ElmNameIdentifierOwner): Boolean {
        return when (element) {
            is ElmFunctionDeclarationLeft -> element.name == "main" || element.isElmTestEntryPoint()
            is ElmPortAnnotation -> isExposed(element)
            else -> false
        }
    }


    private fun markAsUnused(holder: ProblemsHolder, element: ElmNameIdentifierOwner, name: String) {
        val fixes = when (element) {
            is ElmLowerPattern -> arrayOf(RenameToWildcardFix())
            else -> emptyArray()
        }
        holder.registerProblem(
                element.nameIdentifier,
                "'$name' is never used",
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                *fixes
        )
    }
}

/**
 * Detects two common ways of defining a phantom type, where the sole constructor is
 * deliberately impossible to construct and is therefore never expected to be used:
 *
 * ```
 * type MyType = Thing Never
 * type MyOtherType = Foo MyOtherType
 * ```
 *
 * The pattern is a type with exactly one constructor, taking exactly one argument, where the
 * argument is either `Never` (from `Basics`) or the type itself.
 *
 * The pattern comes from elm-review-unused:
 * https://github.com/jfmengels/elm-review-unused/blob/0ae615f97a56cd1218189bb34b7d41a95a0d952d/src/NoUnused/CustomTypeConstructors.elm#L621-L657
 */
private fun isPhantomTypeConstructor(element: ElmNameIdentifierOwner): Boolean {
    val variant = element as? ElmUnionVariant ?: return false
    val typeDeclaration = variant.parentOfType<ElmTypeDeclaration>() ?: return false

    // must be the only constructor, taking a single argument that is a plain type reference
    if (typeDeclaration.unionVariantList.size != 1) return false
    val parameter = variant.allParameters.singleOrNull() as? ElmTypeRef ?: return false
    if (parameter.allArguments.isNotEmpty()) return false

    // the argument must be either the type itself or `Never` (as defined in `Basics`)
    val resolved = parameter.reference.resolve() ?: return false
    if (element.manager.areElementsEquivalent(resolved, typeDeclaration)) return true
    return resolved is ElmTypeDeclaration
            && resolved.name == "Never"
            && resolved.elmFile.getModuleDecl()?.name == "Basics"
}

private fun isPackagePublicApi(element: ElmNameIdentifierOwner): Boolean {
    val decl = element as? ElmExposableTag ?: return false
    val elmProject = decl.elmProject as? ElmPackageProject ?: return false
    val moduleDecl = decl.elmFile.getModuleDecl() ?: return false
    if (moduleDecl.name !in elmProject.exposedModules) return false
    val exposingList = moduleDecl.exposingList ?: return false
    return exposingList.exposes(decl)
}

private class RenameToWildcardFix : NamedQuickFix("Rename to _") {
    override fun applyFix(element: PsiElement, project: Project) {
        (element.parent as? ElmLowerPattern)
                ?.replace(ElmPsiFactory(project).createAnythingPattern())
    }
}

private fun ElmFunctionDeclarationLeft.isElmTestEntryPoint(): Boolean {
    val decl = parentOfType<ElmValueDeclaration>() ?: return false
    if (!decl.isTopLevel) return false
    val typeAnnotation = decl.typeAnnotation ?: return false

    // HACK: string suffix match is very naive, but it's cheap and easy to test.
    // The right thing to do would be to verify that the type resolves to
    // a type declared in the `elm-test` package. But setting that up for
    // `ElmUnusedSymbolInspectionTest` is a pain.
    // TODO revisit this later
    if (!typeAnnotation.text.endsWith(" : Test") && !typeAnnotation.text.endsWith(" : Test.Test"))
        return false

    // The elm-test runner requires that the entry-point be exposed by the module
    return isExposed(this)
}


private fun isExposed(decl: ElmExposableTag): Boolean {
    val exposingList = decl.elmFile.getModuleDecl()?.exposingList ?: return false
    return exposingList.exposes(decl)
}
