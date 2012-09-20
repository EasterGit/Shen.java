package shen;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.lang.System.currentTimeMillis;
import static java.lang.invoke.MethodHandles.Lookup;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.asList;
import static java.util.Arrays.iterable;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Type.*;
import static shen.Shen.*;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond", "SuspiciousNameCombination"})
public class ShenCompiler implements JDK8SafeOpcodes {
    public static class ShenLoader extends ClassLoader {
        public Class<?> define(ShenCode code) {
            ClassWriter cw = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
            code.cn.accept(cw);
            byte[] bytes = cw.toByteArray();
            return super.defineClass(code.cn.name.replaceAll("/", "."), bytes, 0, bytes.length);
        }
    }

    static ShenLoader loader = new ShenLoader();

    public static Object eval(String shen) throws Throwable {
        return new ShenCode(shen).load().call();
    }

    public static CallSite bootstrap(Lookup lookup, String name, MethodType type) {
        return new MutableCallSite(type);
    }

    static Handle bootstrap = new Handle(H_INVOKESTATIC, getInternalName(Shen.class), "bootstrap",
            desc(CallSite.class, Lookup.class, String.class, MethodType.class));

    static String desc(Class<?> returnType, Class<?>... argumentTypes ) {
        return methodType(returnType, argumentTypes).toMethodDescriptorString();
    }

    public static class ShenCode {
        static Map<Symbol, MethodHandle> macros = new HashMap<>();
        static List<Class> literals =
                asList(Double.class, Integer.class, Long.class, String.class, Boolean.class, Handle.class)
                        .into(new ArrayList<Class>());

        static {
            iterable(ShenCode.class.getDeclaredMethods())
                    .filter(m -> isPublic(m.getModifiers()) && m.isAnnotationPresent(Macro.class))
                    .forEach(m -> macro(m));
        }

        String shen;
        ClassNode cn;
        GeneratorAdapter mv;
        Type topOfStack;

        public ShenCode(String shen) throws Throwable {
            this.shen = shen;
            this.cn = classNode();
        }

        ClassNode classNode() {
            ClassNode cn = new ClassNode();
            cn.version = V1_7;
            cn.access = ACC_PUBLIC;
            cn.name = "shen/ShenEval" + currentTimeMillis();
            cn.superName = getInternalName(Object.class);
            cn.interfaces = asList(getInternalName(Callable.class));
            return cn;
        }

        GeneratorAdapter generator(MethodVisitor mv) {
            return generator((MethodNode) mv);
        }

        GeneratorAdapter generator(MethodNode mn) {
            return new GeneratorAdapter(mn, mn.access, mn.name, mn.desc);
        }

        static void macro(java.lang.reflect.Method m)  {
            try {
                macros.put(intern(unscramble(m.getName())), lookup.unreflect(m));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        Type compile(final Object kl) {
            try {
                Class literalClass = some(literals, c -> c.isInstance(kl));
                if (literalClass != null) push(literalClass, kl);
                else if (kl instanceof Symbol) push((Symbol) kl);
                else if (kl instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List) kl;

                    if (list.getFirst() instanceof  Symbol) {
                        Symbol s = (Symbol) list.getFirst();
                        if (macros.containsKey(s)) macros.get(s).bindTo(this).invokeWithArguments(tl(list));
                        else indy(s, tl(list));

                    } else if (kl instanceof Handle) handle((Handle) kl, tl(list));
                } else
                    throw new IllegalArgumentException("Cannot compile: " + kl + " (" + kl.getClass() + ")");

            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
            return topOfStack;
        }

        void indy(Symbol s, List<Object> list) {
            List<Type> argumentTypes = list.map(a -> compile(a)).into(new ArrayList<Type>());

            MethodType type = asMethodType(getType(Object.class), argumentTypes);
            mv.invokeDynamic(s.symbol,type.toMethodDescriptorString() , bootstrap);
            topOfStack(type.returnType());
        }

        void handle(Handle handle, List<Object> list) {
            mv.push(handle);
            mv.push(list.size());
            mv.newArray(getType(Object.class));

            List<Type> argumentTypes = list.map(a ->  {
                mv.dup();
                mv.push(0);
                Type realType = compile(a);
                box();
                mv.arrayStore(getType(Object.class));
                return realType;
            }).into(new ArrayList<Type>());

            mv.push(handle);
            mv.swap();

            mv.invokeVirtual(getType(MethodHandle.class), new Method("invokeWithArguments", desc(Object.class, Object[].class)));

            Type returnType = getReturnType(handle.getDesc());
            mv.checkCast(returnType);
            topOfStack = returnType;
        }

        MethodType asMethodType(Type returnType, List<Type> argumentTypes) {
            return fromMethodDescriptorString(getMethodDescriptor(returnType,
                    argumentTypes.toArray(new Type[argumentTypes.size()])), loader);
        }

        void push(Symbol kl) {
            mv.push(kl.symbol);
            mv.invokeStatic(getType(Shen.class), new Method("intern", desc(Symbol.class, String.class)));
            topOfStack(Symbol.class);
        }

        void push(Class<?> aClass, Object kl) throws Exception {
            aClass = maybePrimitive(aClass);
            mv.getClass().getMethod("push", aClass).invoke(mv, kl);
            topOfStack(aClass);
        }

        void topOfStack(Class<?> aClass) {
            topOfStack = getType(aClass);
        }

        Class<?> maybePrimitive(Class<?> aClass) throws IllegalAccessException {
            try {
                return (Class<?>) aClass.getField("TYPE").get(null);
            } catch (NoSuchFieldException ignore) {
                return aClass;
            }
        }

        public Callable load() throws Exception {
            defaultConstructor();

            mv = generator(cn.visitMethod(ACC_PUBLIC, "call", desc(Object.class), null, null));
            compile(read(shen).getFirst());
            box();
            mv.returnValue();

            return (Callable) loader.define(this).newInstance();
        }

        void box() {
            Type maybePrimitive = topOfStack;
            mv.box(maybePrimitive);
            topOfStack = boxedType(maybePrimitive);
        }

        Type boxedType(Type type) {
            try {
                java.lang.reflect.Method getBoxedType = GeneratorAdapter.class.getDeclaredMethod("getBoxedType", Type.class);
                getBoxedType.setAccessible(true);
                return (Type) getBoxedType.invoke(null, type);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void defaultConstructor() {
            GeneratorAdapter ctor = generator(cn.visitMethod(ACC_PUBLIC, "<init>", desc(void.class), null, null));
            ctor.loadThis();
            ctor.invokeConstructor(getType(Object.class), new Method("<init>", desc(void.class)));
            ctor.returnValue();
        }

        @Macro
        public void trap_error(Object x, Object f) throws Throwable {
            Label start = mv.newLabel();
            Label end = mv.newLabel();
            Label after = mv.newLabel();

            mv.visitLabel(start);
            compile(x);
            box();
            mv.goTo(after);
            mv.visitLabel(end);

            mv.catchException(start, end, getType(Exception.class));
            int e = mv.newLocal(getType(Exception.class));
            mv.storeLocal(e);
            compile(f);
            mv.checkCast(getType(MethodHandle.class));
            mv.loadLocal(e);
            mv.invokeVirtual(getType(MethodHandle.class), new Method("invoke", desc(Object.class, Object.class)));

            mv.visitLabel(after);
        }

        @Macro
        public void kl_if(Object test, Object then, Object _else) throws Exception {
            Label elseStart = mv.newLabel();
            Label end = mv.newLabel();

            compile(test);
            if (!topOfStack.equals(getType(boolean.class)))
                mv.unbox(getType(Boolean.class));
            mv.visitJumpInsn(IFEQ, elseStart);

            compile(then);
            mv.goTo(end);

            mv.visitLabel(elseStart);
            compile(_else);

            mv.visitLabel(end);
        }
    }

    public static void main(String[] args) throws Throwable {
        System.out.println(eval("(trap-error my-symbol my-handler)"));
        System.out.println(eval("(if true \"true\" \"false\")"));
        System.out.println(eval("(if false \"true\" \"false\")"));
    }
}

interface JDK8SafeOpcodes {
    int V1_7 = 51;
    int ACC_PUBLIC = 1;
    int H_INVOKESTATIC = 6;
    int IFEQ = 153;
}
