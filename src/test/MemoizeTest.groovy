package example

import java.lang.reflect.Modifier
import org.codehaus.groovy.control.MultipleCompilationErrorsException

class MemoizeTest extends GroovyTestCase {
    private def strFibClassDef = '''
        class FibClass {
            @example.Memoize
            def fib(n) {
                n <= 1 ? n : fib(n-1) + fib(n-2)
            }
        }
    '''
    private def strFibClassNew = strFibClassDef + 'new FibClass()'

    private def strPascalsTriangleClassNew = '''
        class PascalsTriangleClass {
            @example.Memoize
            def calc(n, k) {
                n == 0 ? 1 :
                k == 0 ? 1 :
                k == n ? 1 :
                calc(n - 1, k) + calc(n - 1, k - 1)
            }
        }
        new PascalsTriangleClass()
    '''

    public void testMemoizeMemoFieldProperty() {
        def tester = new GroovyClassLoader().parseClass(strFibClassDef)
        def field = tester.getDeclaredField('memofib')

        assert !Modifier.isPublic(field.modifiers)
        assert Modifier.isPrivate(field.modifiers)
        assert !Modifier.isProtected(field.modifiers)
        assert !Modifier.isStatic(field.modifiers) // memofield is static if and only if the annotated method is static
        assert Modifier.isFinal(field.modifiers)
        assert Modifier.isTransient(field.modifiers)
        assert Modifier.isSynthetic(field.modifiers)
        assert field.type == Map
    }

    public void testMemoizeDoesMemoization() {
        def tester = new GroovyShell().evaluate(strFibClassNew)

        assert tester.memofib.isEmpty()     // at first, memo should be empty
        assert tester.fib(4) == 3           // memoizing does not change the return value
        assert tester.memofib == [0:0, 1:1, 2:1, 3:2, 4:3]  // test if the result is memoized

        tester.memofib[4] = 42      // change the memoized value in order to test if memoized value is referenced
        assert tester.fib(4) == 42
    }

    public void testMemoizeWhenTargetMethodHasMoreThanOneParameters() {
        def tester = new GroovyShell().evaluate(strPascalsTriangleClassNew)

        assert tester.memocalc.isEmpty()
        assert tester.calc(3, 2) == 3

        assert tester.memocalc.sort{it.key} == [
                [1, 0] : 1, [1, 1] : 1,
                            [2, 1] : 2, [2, 2] : 1,
                                        [3, 2] : 3].sort{it.key}

        // change the memoized value in order to test if memoized value is referenced
        tester.memocalc[[2, 2]] = 42
        assert tester.calc(2, 2) == 42
    }

    public void testMemoizeIfTargetMethodIsStaticThenMemoFieldIsAlsoStatic() {
        def tester = new GroovyClassLoader().parseClass('''
            class StaticMethodClass {
                @example.Memoize
                static staticMethod(a) { a }
            }
        ''')
        def field = tester.getDeclaredField('memostaticMethod')
        assert !Modifier.isPublic(field.modifiers)
        assert Modifier.isPrivate(field.modifiers)
        assert !Modifier.isProtected(field.modifiers)
        assert Modifier.isStatic(field.modifiers) // memofield is static if and only if the annotated method is static
        assert Modifier.isFinal(field.modifiers)
        assert Modifier.isTransient(field.modifiers)
        assert Modifier.isSynthetic(field.modifiers)
        assert field.type == Map
    }

    public void testMemoizeFailsIfAppliedToVoidMethod() {
        assert shouldFail(MultipleCompilationErrorsException) {
            new GroovyClassLoader().parseClass('''
            class VoidMethodClass {
                @example.Memoize
                void voidMethod(a) {
                    println("Hi!, I'm a void method.")
                }
            }
            ''')
        }.contains('cannot @Memoize a void method: voidMethod')
    }

    public void testMemoizeFailsIfAppliedToMethodWithoutParameters() {
        assert shouldFail(MultipleCompilationErrorsException) {
            new GroovyClassLoader().parseClass('''
            class EmptyParameterMethodClass {
                @example.Memoize
                def methodWithoutParameter() {
                    "I have no parameters"
                }
            }
            ''')
        }.contains('cannot @Memoize a method with no parameters: methodWithoutParameter')
    }
}
