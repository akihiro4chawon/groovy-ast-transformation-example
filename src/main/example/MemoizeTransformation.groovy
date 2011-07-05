package example

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.control.*
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.transform.*
import static org.objectweb.asm.Opcodes.*

@GroovyASTTransformation(phase = CompilePhase.INSTRUCTION_SELECTION)
public class MemoizeTransformation implements ASTTransformation {

    void visit(ASTNode[] astNodes, SourceUnit sourceUnit) {
        // defensive programming
        if (!astNodes || !astNodes[0] || !astNodes[1]) return
        if (!(astNodes[0] instanceof AnnotationNode)) return
        if (!(astNodes[1] instanceof MethodNode)) return

        // validate annotationed method
        MethodNode annotatedMethod = astNodes[1]
        def registerError = { errMsg ->sourceUnit.errorCollector.addError(
            Message.create("$errMsg: $annotatedMethod.name", sourceUnit))
        }
        if (annotatedMethod.parameters.length == 0) {
            registerError 'cannot @Memoize a method with no parameters'
            return
        }
        if (annotatedMethod.returnType.name == "void") {
            registerError 'cannot @Memoize a void method'
            return
        }

        ClassNode declaringClass = annotatedMethod.declaringClass
        makeMethodMemoized(declaringClass, annotatedMethod)
    }

    private void aliasMethod(String alias, MethodNode org) {
        org.declaringClass.addMethod(
            new MethodNode(alias, org.modifiers, org.returnType, org.parameters, org.exceptions, org.code))
    }

    private Statement createMemoizingWrapperCode(MethodNode methodNode, String methodBackupName, String memoFieldName) {
        def memoizedValueName = 'memoizedValue'
        def computedValueName = 'computedValue'
        Parameter[] params = methodNode.parameters

        def arguments = params.collect{ new VariableExpression(it.name) }
        def argsAsSingle = (params.length == 1) ?
            arguments.head() :
            new ListExpression(arguments)

        // メモ化処理をするラッパーコード
        def memoizingCode = new AstBuilder().buildFromSpec { block {
            expression {
                declaration {
                    variable memoizedValueName
                    token "="
                    methodCall {
                        variable memoFieldName
                        constant 'get'
                        argumentList {
                            owner.expression.add(argsAsSingle)
                        }
                    }
                }
            }
            ifStatement { // if memoized then {return memoized_value} else {compute -> store -> return}
                booleanExpression { // condition clause
                    variable memoizedValueName
                }
                returnStatement {   // if-body
                    variable memoizedValueName
                }
                block {             // else-body
                    expression {
                        declaration {
                            variable computedValueName
                            token '='
                            methodCall {
                                variable "this"
                                constant methodBackupName
                                argumentList {
                                    owner.expression.addAll(arguments)
                                }
                            }
                        }
                    }
                    expression {
                        methodCall {
                            variable memoFieldName
                            constant 'put'
                            argumentList {
                                owner.expression.add(argsAsSingle)
                                variable computedValueName
                            }
                        }
                    }
                    returnStatement {
                        variable computedValueName
                    }
                }
            }
        }}
        memoizingCode.head()
    }

    private void makeMethodMemoized(ClassNode classNode, MethodNode methodNode) {
        // add field of memo map
        def memoFieldModifiers = ACC_PRIVATE | ACC_FINAL | ACC_TRANSIENT | ACC_SYNTHETIC | (methodNode.modifiers & ACC_STATIC)
        def memoFieldName = "memo${methodNode.name}"
        classNode.addField("$memoFieldName", memoFieldModifiers, new ClassNode(Map.class),
            new ConstructorCallExpression(new ClassNode(HashMap.class), new ArgumentListExpression()))

        // 本体を保存する
        def methodName = methodNode.name
        def methodBackupName = "${methodName}WithoutMemo"
        aliasMethod(methodBackupName, methodNode)

        // ラッパーコードで置き換える
        methodNode.setCode(createMemoizingWrapperCode(methodNode, methodBackupName, memoFieldName))
    }
}

