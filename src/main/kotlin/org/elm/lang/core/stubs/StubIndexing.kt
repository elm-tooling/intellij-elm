package org.elm.lang.core.stubs

import com.intellij.psi.stubs.IndexSink
import org.elm.lang.core.stubs.index.ElmModules
import org.elm.lang.core.stubs.index.KEY


fun IndexSink.indexModuleDecl(stub: ElmModuleDeclarationStub) {
    indexNamedStub(stub)
    ElmModules.index(stub, this)
}

fun IndexSink.indexFuncDecl(stub: ElmFunctionDeclarationLeftStub) {
    indexNamedStub(stub)
}

fun IndexSink.indexInfixDecl(stub: ElmInfixDeclarationStub) {
    indexNamedStub(stub)
}

fun IndexSink.indexTypeDecl(stub: ElmTypeDeclarationStub) {
    indexNamedStub(stub)
}

fun IndexSink.indexTypeAliasDecl(stub: ElmTypeAliasDeclarationStub) {
    indexNamedStub(stub)
}

fun IndexSink.indexUnionVariant(stub: ElmUnionVariantStub) {
    indexNamedStub(stub)
}

fun IndexSink.indexPortAnnotation(stub: ElmPortAnnotationStub) {
    indexNamedStub(stub)
}

fun IndexSink.indexRecordField(stub: ElmFieldTypeStub) {
    indexNamedStub(stub)
}


private fun IndexSink.indexNamedStub(stub: ElmNamedStub) {
    occurrence(KEY, stub.name)
}
