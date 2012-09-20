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
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.functions.Mapper;

import static java.lang.System.out;
import static java.lang.invoke.MethodHandles.Lookup;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.fromMethodDescriptorString;
import static java.lang.invoke.MethodType.methodType;
import static java.lang.reflect.Modifier.isPublic;
import static java.util.Arrays.*;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Type.*;
import static shen.Shen.*;

@SuppressWarnings({"UnusedDeclaration", "Convert2Diamond", "SuspiciousNameCombination"})
public class ShenCompiler implements JDK8SafeOpcodes {
    public static class ShenLoader extends ClassLoader {
        public Class<?> define(ClassNode cn) {
            ClassWriter cw = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
            cn.accept(cw);
            byte[] bytes = cw.toByteArray();
            return super.defineClass(cn.name.replaceAll("/", "."), bytes, 0, bytes.length);
        }
    }

    static ShenLoader loader = new ShenLoader();

    public static Object eval(String shen) throws Throwable {
        return new ShenCode(read(shen).getFirst()).load(Callable.class).newInstance().call();
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

        static int id = 1;

        Object shen;
        List<Symbol> scope;
        List<Symbol> args;
        GeneratorAdapter mv;
        Type topOfStack;
        ClassNode cn;
        Label recur;

        public ShenCode(Object shen, List<Symbol> scope, Symbol... args) throws Throwable {
            this.shen = shen;
            this.scope = scope;
            this.args = list(args);
        }

        public ShenCode(Object shen, Symbol... args) throws Throwable {
            this(shen, new ArrayList<>(), args);
        }

        ClassNode classNode(Class<?> anInterface) {
            ClassNode cn = new ClassNode();
            cn.version = V1_7;
            cn.access = ACC_PUBLIC;
            cn.name = "shen/ShenEval" + id++;
            cn.superName = getInternalName(Object.class);
            cn.interfaces = asList(getInternalName(anInterface));
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
            //noinspection CaughtExceptionImmediatelyRethrown
            try {
                Class literalClass = some(literals, c -> c.isInstance(kl));
                if (literalClass != null) push(literalClass, kl);
                else if (kl instanceof Symbol) symbol((Symbol) kl);
                else if (kl instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List) kl;

                    if (list.getFirst() instanceof Symbol) {
                        Symbol s = (Symbol) list.getFirst();
                        if (macros.containsKey(s)) macroExpand(s, tl(list));
                        else indy(s, tl(list));

                    } else {
                        compile(list.getFirst());
                        apply(tl(list));
                    }
                } else
                    throw new IllegalArgumentException("Cannot compile: " + kl + " (" + kl.getClass() + ")");
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            return topOfStack;
        }

        void symbol(Symbol s) {
            if (args.contains(s)) {
                mv.loadArg(args.indexOf(s));
                topOfStack(Object.class);
            } else if (scope.contains(s)) {
                mv.loadThis();
                mv.getField(getObjectType(cn.name), s.symbol, getType(Object.class));
                topOfStack(Object.class);
            } else
                push(s);
        }

        void macroExpand(Symbol s, List<Object> args) throws Throwable {
            bindTo(macros.get(s), this).invokeWithArguments(args);
        }

        void indy(Symbol s, List<Object> args) {
            List<Type> argumentTypes = args.map(a -> compile(a)).into(new ArrayList<Type>());

            MethodType type = asMethodType(getType(Object.class), argumentTypes);
            mv.invokeDynamic(s.symbol, type.toMethodDescriptorString(), bootstrap);
            topOfStack(type.returnType());
        }

        void apply(List<Object> args) {
            mv.checkCast(getType(MethodHandle.class));

            mv.push(args.size());
            mv.newArray(getType(Object.class));

            for (int i = 0; i < args.size(); i++) {
                mv.dup();
                mv.push(i);
                compile(args.get(i));
                box();
                mv.arrayStore(getType(Object.class));
            }

            mv.invokeStatic(getType(ShenCode.class), new Method("apply", desc(Object.class, MethodHandle.class, Object[].class)));
            topOfStack = getType(Object.class);
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

        public <T> Class<T> load(Class<T> anInterface) throws Exception {
            cn = classNode(anInterface);
            constructor();

            java.lang.reflect.Method sam = findSAM(anInterface);
            mv = generator(cn.visitMethod(ACC_PUBLIC, sam.getName(), getMethodDescriptor(sam), null, null));
            recur = mv.newLabel();
            mv.visitLabel(recur);
            compile(shen);
            if (!sam.getReturnType().isPrimitive()) box();
            mv.returnValue();

            //noinspection unchecked
            return (Class<T>) loader.define(cn);
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

        void constructor() {
            Class[] args = fillArray(Object.class, this.scope.size());
            GeneratorAdapter ctor = generator(cn.visitMethod(ACC_PUBLIC, "<init>", desc(void.class, args), null, null));

            for (int i = 0; i < scope.size(); i++) {
                cn.visitField(ACC_PUBLIC | ACC_FINAL, scope.get(i).symbol, getDescriptor(Object.class), null, null);
                ctor.loadThis();
                ctor.loadArg(i);
                ctor.putField(getObjectType(cn.name), scope.get(i).symbol, getType(Object.class));
            }

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
            bindTo();
            mv.push((String) null);
            mv.invokeVirtual(getType(MethodHandle.class), new Method("apply", desc(Object.class, Object[].class)));

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

        @Macro
        public void cond(List... clauses) throws Exception {
            if (clauses.length == 0) {
                mv.throwException(getType(IllegalArgumentException.class), "condition failure");
            } else {
                kl_if(hd(clauses).getFirst(), hd(clauses).get(1), cons(intern("cond"), list(tl(clauses))));
            }
        }

        @Macro
        public void or(Object x, Object... clauses) throws Exception {
            if (clauses.length == 0)
                bindTo(staticMH(ShenCode.class, "or", desc(boolean.class, boolean.class, boolean[].class)), x);
            else
                kl_if(x, true, (clauses.length > 1 ? cons(intern("or"), list(clauses)) : clauses[0]));
        }

        @Macro
        public void and(Object x, Object... clauses) throws Exception {
            if (clauses.length == 0)
                bindTo(staticMH(ShenCode.class, "and", desc(boolean.class, boolean.class, boolean[].class)), x);
            else
                kl_if(x, (clauses.length > 1 ? cons(intern("and"), list(clauses)) : clauses[0]), false);
        }

        @Macro
        public void lambda(Symbol x, Object y) throws Throwable {
            List<Symbol> scope = args.into(new ArrayList<>(this.scope));
            Class<Mapper> lambda = new ShenCode(y, scope, x).load(Mapper.class);
            java.lang.reflect.Method sam = findSAM(lambda);

            mv.push(new Handle(H_INVOKEVIRTUAL, getInternalName(lambda), sam.getName(), getMethodDescriptor(sam)));

            Constructor<Mapper> ctor = lambda.getConstructor(fillArray(Object.class, scope.size()));
            mv.newInstance(getType(ctor.getDeclaringClass()));
            mv.dup();
            scope.forEach(s -> {compile(s);});
            mv.invokeConstructor(getType(ctor.getDeclaringClass()), new Method("<init>", getConstructorDescriptor(ctor)));
            bindTo();

            topOfStack(MethodHandle.class);
        }

        @Macro
        public void let(Symbol x, Object y, Object z) throws Throwable {
            lambda(x, z);
            apply(asList(y));
        }

        Class[] fillArray(Class value, int elements) {
            Class[] args = new Class[elements];
            fill(args, value);
            return args;
        }

        Handle staticMH(Class aClass, String name, String desc) {
            return new Handle(H_INVOKESTATIC, getInternalName(aClass), name, desc);
        }

        void bindTo(Handle handle, Object arg) {
            mv.push(handle);
            compile(arg);
            box();
            bindTo();
        }

        void bindTo() {
            mv.invokeStatic(getType(ShenCode.class), new Method("bindTo", desc(MethodHandle.class, MethodHandle.class, Object.class)));
        }

        // RT
        public static Object apply(MethodHandle fn, Object...  args) throws Throwable {
            return Shen.apply(fn, asList(args));
        }

        public static MethodHandle bindTo(MethodHandle fn, Object arg) {
            return fn.isVarargsCollector() ?
                    insertArguments(fn, 0, arg).asVarargsCollector(fn.type().parameterType(fn.type().parameterCount() - 1)) :
                    insertArguments(fn, 0, arg);
        }

        public static boolean or(boolean x, boolean... clauses) throws Exception {
            if (x) return true;
            for (boolean b : clauses) if (b) return true;
            return false;
        }

        public static boolean and(boolean x, boolean... clauses) throws Exception {
            if (!x) return false;
            for (boolean b : clauses) if (!b) return false;
            return true;
        }
    }

    public static void main(String[] args) throws Throwable {
        out.println(eval("(trap-error my-symbol my-handler)"));
        out.println(eval("(if true \"true\" \"false\")"));
        out.println(eval("(if false \"true\" \"false\")"));
        out.println(eval("(cond (false 1) (true 2))"));
        out.println(eval("(cond (false 1) ((or true false) 3))"));
        out.println(eval("(or false)"));
        out.println(((MethodHandle) eval("(or false)")).invokeWithArguments(false, true));
        out.println(eval("((or false) false)"));
        out.println(eval("(or false false)"));
        out.println(eval("(or false true false)"));
        out.println(eval("(and true true)"));
        out.println(eval("(and true true (or false false))"));
        out.println(eval("(and true false)"));
        out.println(eval("(and true)"));
        out.println(eval("(lambda x x)"));
        out.println(eval("((lambda x x) 2)"));
        out.println(eval("(let x 10 x)"));
        out.println(eval("(let x 10 (let y 5 x))"));
        out.println(eval("((let x 42 (lambda y x)) 0)"));
        out.println(eval("((lambda x ((lambda y x) 42)) 0)"));
    }
}

interface JDK8SafeOpcodes {
    int V1_7 = 51;
    int ACC_PUBLIC = 0x0001;
    int ACC_FINAL = 0x0010;
    int H_INVOKEVIRTUAL = 5;
    int H_INVOKESTATIC = 6;
    int IFEQ = 153;
}
