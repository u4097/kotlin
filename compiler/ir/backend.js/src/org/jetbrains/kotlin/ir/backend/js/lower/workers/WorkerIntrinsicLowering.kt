/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower.workers

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.descriptors.*
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.SymbolWithIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.SourceManager
import org.jetbrains.kotlin.ir.SourceRangeInfo
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.ir.addChild
import org.jetbrains.kotlin.ir.backend.js.ir.simpleFunctions
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrConstructorSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

class WorkerIntrinsicLowering(val context: JsIrBackendContext) : FileLoweringPass {
    private object WORKER_IMPL_ORIGIN : IrDeclarationOriginImpl("WORKER_IMPL")

    val additionalFiles = arrayListOf<IrFile>()

    private val workerInterface = context.ir.symbols.workerInterface
    private val jsCodeSymbol = context.symbolTable.referenceSimpleFunction(context.getInternalFunctions("js").single())

    private var counter = 0

    private lateinit var caller: IrFunction

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                val call = super.visitCall(expression)
                if (call is IrCall && expression.descriptor.isWorker()) {
                    val param = call.getValueArgument(0) as? IrBlock ?: error("worker intrinsic accepts only block, but got $call")
                    val wrapper = createWorkerWrapper(param)
                    moveToSeparateFile(param, wrapper.name, wrapper)
                    context.implicitDeclarationFile.declarations.add(wrapper)
                    return callWrapperConstructor(wrapper, call, createNewWorker(wrapper))
                } else {
                    return call
                }
            }

            override fun visitFunction(declaration: IrFunction): IrStatement {
                caller = declaration
                return super.visitFunction(declaration)
            }
        })
    }

    private fun moveToSeparateFile(param: IrBlock, name: Name, worker: IrClass) {
        val file = newFile(name.asString())
        createWorkerContentBuilder(param, file, worker).also { it.initialize(); file.declarations.add(it.ir) }
    }

    private fun newFile(name: String): IrFile {
        val file = IrFileImpl(object : SourceManager.FileEntry {
            override val name = "<${name}_blob>"
            override val maxOffset = UNDEFINED_OFFSET

            override fun getSourceRangeInfo(beginOffset: Int, endOffset: Int) =
                SourceRangeInfo(
                    "",
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET
                )

            override fun getLineNumber(offset: Int) = UNDEFINED_OFFSET
            override fun getColumnNumber(offset: Int) = UNDEFINED_OFFSET
        }, context.internalPackageFragmentDescriptor)
        additionalFiles.add(file)
        return file
    }

    private fun createWorkerContentBuilder(param: IrBlock, file: IrFile, worker: IrClass) =
        object : SymbolWithIrBuilder<IrSimpleFunctionSymbol, IrSimpleFunction>() {
            private val descriptor = WrappedSimpleFunctionDescriptor()

            override fun buildSymbol() = IrSimpleFunctionSymbolImpl(descriptor)

            override fun buildIr(): IrSimpleFunction {
                val declaration = IrFunctionImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = WORKER_IMPL_ORIGIN,
                    symbol = symbol,
                    name = Name.identifier("onmessageImpl"),
                    visibility = Visibilities.PUBLIC,
                    modality = Modality.FINAL,
                    returnType = context.irBuiltIns.unitType,
                    isInline = false,
                    isExternal = false,
                    isTailrec = false,
                    isSuspend = false
                )
                descriptor.bind(declaration)
                declaration.parent = worker
                declaration.valueParameters += JsIrBuilder.buildValueParameter(
                    name = "it",
                    index = 0,
                    type = context.irBuiltIns.anyType
                ).also { it.parent = declaration }

                declaration.body = JsIrBuilder.buildBlockBody(
                    (param.statements.filterIsInstance<IrFunction>().single().body!!.deepCopyWithSymbols(declaration) as IrStatementContainer).statements
                )
                return declaration
            }
        }

    private fun callWrapperConstructor(
        wrapper: IrClass,
        call: IrExpression,
        worker: IrCallImpl
    ): IrCallImpl {
        val constructor = wrapper.constructors.first()
        return IrCallImpl(
            startOffset = call.startOffset,
            endOffset = call.endOffset,
            type = wrapper.defaultType,
            symbol = constructor.symbol
        ).apply {
            putValueArgument(0, worker)
        }
    }

    private fun createNewWorker(wrapper: IrClass) = IrCallImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = context.irBuiltIns.anyType,
        symbol = jsCodeSymbol
    ).apply {
        putValueArgument(0, JsIrBuilder.buildString(context.irBuiltIns.stringType, "new Worker(${wrapper.name}_blob)"))
    }

    /* Create the following class
     * class Worker$<tmpName>: Worker {
     *   val inner: Any
     *   constructor(tmp: Any) {
     *     inner = tmp
     *   )
     *   override fun postMessage(message: any) {
     *     js("inner.postMessage(message)
     *   }
     * }
     */
    private fun createWorkerWrapper(block: IrBlock): IrClass {
        val classDescriptor = WrappedClassDescriptor()
        val workerClass = IrClassImpl(
            startOffset = block.startOffset,
            endOffset = block.endOffset,
            origin = WORKER_IMPL_ORIGIN,
            symbol = IrClassSymbolImpl(classDescriptor),
            name = "WorkerImpl\$${counter++}".synthesizedName,
            kind = ClassKind.CLASS,
            visibility = Visibilities.PUBLIC,
            modality = Modality.FINAL,
            isCompanion = false,
            isInner = false,
            isData = false,
            isExternal = false,
            isInline = false
        )
        classDescriptor.bind(workerClass)
        workerClass.parent = caller.parent
        workerClass.superTypes += mutableListOf(workerInterface.owner.defaultType)
        val thisType = IrSimpleTypeImpl(workerClass.symbol, false, emptyList(), emptyList())
        val workerClassThis = JsIrBuilder.buildValueParameter(
            name = Name.special("<this>"),
            index = -1,
            type = thisType,
            origin = IrDeclarationOrigin.INSTANCE_RECEIVER
        )
        workerClass.thisReceiver = workerClassThis
        workerClassThis.parent = workerClass
        val innerField = createInnerField(workerClass)
        createConstructorBuilder(workerClass, workerClassThis, innerField).also { it.initialize(); workerClass.addChild(it.ir) }
        createPostMessageBuilder(workerClass, workerClassThis).also { it.initialize(); workerClass.addChild(it.ir) }
        createOnmessageBuilder(workerClass, workerClassThis).also { it.initialize(); workerClass.addChild(it.ir) }
        return workerClass
    }

    private fun createOnmessageBuilder(workerClass: IrClassImpl, workerClassThis: IrValueParameter) =
        object : SymbolWithIrBuilder<IrSimpleFunctionSymbol, IrSimpleFunction>() {
            private val descriptor = WrappedSimpleFunctionDescriptor()

            override fun buildSymbol() = IrSimpleFunctionSymbolImpl(descriptor)

            override fun buildIr(): IrSimpleFunction {
                val declaration = IrFunctionImpl(
                    startOffset = workerClass.startOffset,
                    endOffset = workerClass.endOffset,
                    origin = WORKER_IMPL_ORIGIN,
                    symbol = symbol,
                    name = Name.identifier("onmessage"),
                    visibility = Visibilities.PUBLIC,
                    modality = Modality.FINAL,
                    returnType = context.irBuiltIns.unitType,
                    isInline = false,
                    isExternal = false,
                    isTailrec = false,
                    isSuspend = false
                )
                descriptor.bind(declaration)
                declaration.parent = workerClass
                declaration.dispatchReceiverParameter = workerClassThis.copyTo(declaration)
                val superDeclaration = workerInterface.owner.simpleFunctions().single { it.name.asString() == "onmessage" }
                declaration.valueParameters += JsIrBuilder.buildValueParameter(
                    name = "c",
                    index = 0,
                    type = superDeclaration.valueParameters.first().type
                ).also { it.parent = declaration }

                declaration.overriddenSymbols += superDeclaration.overriddenSymbols
                declaration.overriddenSymbols += superDeclaration.symbol

                declaration.body = context.createIrBuilder(symbol, declaration.startOffset, declaration.endOffset).irBlockBody(
                    startOffset = UNDEFINED_OFFSET, endOffset = UNDEFINED_OFFSET
                ) {
                    +IrCallImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = context.irBuiltIns.anyType,
                        symbol = jsCodeSymbol
                    ).apply {
                        putValueArgument(
                            0,
                            JsIrBuilder.buildString(
                                context.irBuiltIns.stringType,
                                "this.inner_${workerClass.name}.onmessage = function(it) { c(it) }"
                            )
                        )
                    }
                }
                return declaration
            }
        }

    private fun createInnerField(wrapperClass: IrClassImpl): IrField {
        val descriptor = WrappedPropertyDescriptor()
        val symbol = IrFieldSymbolImpl(descriptor)
        return IrFieldImpl(
            startOffset = wrapperClass.startOffset,
            endOffset = wrapperClass.endOffset,
            origin = WORKER_IMPL_ORIGIN,
            symbol = symbol,
            name = Name.identifier("inner"),
            type = context.irBuiltIns.anyType,
            visibility = Visibilities.PRIVATE,
            isFinal = true,
            isExternal = false,
            isStatic = false
        ).also {
            descriptor.bind(it)
            it.parent = wrapperClass
            wrapperClass.addChild(it)
        }
    }

    private fun createPostMessageBuilder(workerClass: IrClassImpl, workerClassThis: IrValueParameter) =
        object : SymbolWithIrBuilder<IrSimpleFunctionSymbol, IrSimpleFunction>() {
            private val descriptor = WrappedSimpleFunctionDescriptor()

            override fun buildSymbol() = IrSimpleFunctionSymbolImpl(descriptor)

            override fun buildIr(): IrSimpleFunction {
                val declaration = IrFunctionImpl(
                    startOffset = workerClass.startOffset,
                    endOffset = workerClass.endOffset,
                    origin = WORKER_IMPL_ORIGIN,
                    symbol = symbol,
                    name = Name.identifier("postMessage"),
                    visibility = Visibilities.PUBLIC,
                    modality = Modality.FINAL,
                    returnType = context.irBuiltIns.unitType,
                    isInline = false,
                    isExternal = false,
                    isTailrec = false,
                    isSuspend = false
                )
                descriptor.bind(declaration)
                declaration.parent = workerClass
                declaration.dispatchReceiverParameter = workerClassThis.copyTo(declaration)
                declaration.valueParameters += JsIrBuilder.buildValueParameter(
                    name = "message",
                    index = 0,
                    type = context.irBuiltIns.anyType
                ).also { it.parent = declaration }

                val superDeclaration = workerInterface.owner.simpleFunctions().single { it.name.asString() == "postMessage" }
                declaration.overriddenSymbols += superDeclaration.overriddenSymbols
                declaration.overriddenSymbols += superDeclaration.symbol

                declaration.body = context.createIrBuilder(symbol, declaration.startOffset, declaration.endOffset).irBlockBody(
                    startOffset = UNDEFINED_OFFSET, endOffset = UNDEFINED_OFFSET
                ) {
                    +IrCallImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = context.irBuiltIns.anyType,
                        symbol = jsCodeSymbol
                    ).apply {
                        putValueArgument(
                            0,
                            JsIrBuilder.buildString(
                                context.irBuiltIns.stringType,
                                "this.inner_${workerClass.name}.postMessage(message)"
                            )
                        )
                    }
                }
                return declaration
            }
        }

    private fun createConstructorBuilder(
        workerClass: IrClass,
        workerClassThis: IrValueParameter,
        innerField: IrField
    ) = object : SymbolWithIrBuilder<IrConstructorSymbol, IrConstructor>() {
        private val descriptor = WrappedClassConstructorDescriptor()

        override fun buildSymbol(): IrConstructorSymbol = IrConstructorSymbolImpl(descriptor)

        override fun doInitialize() {}

        override fun buildIr(): IrConstructor {
            val declaration = IrConstructorImpl(
                startOffset = workerClass.startOffset,
                endOffset = workerClass.endOffset,
                origin = WORKER_IMPL_ORIGIN,
                symbol = symbol,
                name = Name.special("<init>"),
                visibility = Visibilities.PUBLIC,
                returnType = workerClass.defaultType,
                isInline = false,
                isExternal = false,
                isPrimary = false
            )
            descriptor.bind(declaration)
            declaration.parent = workerClass
            val valueParameter = JsIrBuilder.buildValueParameter(
                index = 0,
                type = context.irBuiltIns.anyType
            ).also { it.parent = declaration }
            declaration.valueParameters += valueParameter
            declaration.body = context.createIrBuilder(symbol, declaration.startOffset, declaration.endOffset).irBlockBody(
                startOffset = UNDEFINED_OFFSET, endOffset = UNDEFINED_OFFSET
            ) {
                +irSetField(
                    irGet(workerClassThis),
                    innerField,
                    irGet(valueParameter)
                )
            }
            return declaration
        }
    }
}

private fun FunctionDescriptor.isWorker(): Boolean = isTopLevelInPackage("worker", "kotlin.worker")
