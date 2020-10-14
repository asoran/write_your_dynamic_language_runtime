package fr.umlv.smalljs.jvminterp;

import static java.lang.invoke.MethodType.genericMethodType;
import static org.objectweb.asm.Opcodes.*;

import java.io.PrintWriter;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.List;

import fr.umlv.smalljs.rt.Failure;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.CheckClassAdapter;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Instr;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.VoidVisitor;
import fr.umlv.smalljs.rt.JSObject;

public class ByteCodeRewriter {
    private final VoidVisitor<JSObject> visitor;

    private ByteCodeRewriter(MethodVisitor mv, FunDictionary dictionary) {
        this.visitor = createVisitor(mv, dictionary);
    }

    public static JSObject createFunction(String name, List<String> parameters, Block body, JSObject global) {
        var env = JSObject.newEnv(null);

        env.register("this", 0);
        for (String parameter : parameters) {
            env.register(parameter, env.length());
        }
        var parameterCount = env.length();
        visitVariable(body, env);
        var localVariableCount = env.length();

        var cv = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cv.visit(V11, ACC_PUBLIC | ACC_SUPER, "script", null, "java/lang/Object", null);
        cv.visitSource("script", null);

        var methodType = genericMethodType(1 + parameters.size());
        var desc = methodType.toMethodDescriptorString();
        var mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();

        //initialize local variables to undefined by default
        for(var i = parameterCount; i < localVariableCount; i++) {
          mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
          mv.visitVarInsn(ASTORE, i);
        }

        var dictionary = new FunDictionary();
        var rewriter = new ByteCodeRewriter(mv, dictionary);
        rewriter.visitor.visit(body, env);

        mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        var instrs = cv.toByteArray();
        dumpBytecode(instrs);

        var functionClassLoader = new FunClassLoader(dictionary, global);
        var type = functionClassLoader.createClass("script", instrs);

        MethodHandle mh;
        try {
            mh = MethodHandles.lookup().findStatic(type, name, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }

        return JSObject.newFunction(name, mh);
    }

    private static void dumpBytecode(byte[] array) {
        ClassReader reader = new ClassReader(array);
        CheckClassAdapter.verify(reader, true, new PrintWriter(System.err));
    }

    private static void visitVariable(Expr expr, JSObject env) {
        VARIABLE_VISITOR.visit(expr, env);
    }

    private static final VoidVisitor<JSObject> VARIABLE_VISITOR =
            new VoidVisitor<JSObject>()
                    .when(Block.class, (block, env) -> {
                        for (Expr instr : block.instrs()) {
                            visitVariable(instr, env);
                        }
                    })
                    .when(Literal.class, (literal, env) -> {
                        // do nothing
                    })
                    .when(FunCall.class, (funCall, env) -> {
                        // do nothing
                    })
                    .when(LocalVarAssignment.class, (localVarAssignment, env) -> {
                        if (localVarAssignment.declaration()) {
                            env.register(localVarAssignment.name(), env.length());
                        }
                    })
                    .when(LocalVarAccess.class, (localVarAccess, env) -> {
                        // do nothing
                    })
                    .when(Fun.class, (fun, env) -> {
                        // do nothing
                    })
                    .when(Return.class, (_return, env) -> {
                        // do nothing
                    })
                    .when(If.class, (_if, env) -> {
                        visitVariable(_if.trueBlock(), env);
                        visitVariable(_if.falseBlock(), env);
                    })
                    .when(New.class, (_new, env) -> {
                        // do nothing
                    })
                    .when(FieldAccess.class, (fieldAccess, env) -> {
                        // do nothing
                    })
                    .when(FieldAssignment.class, (fieldAssignment, env) -> {
                        // do nothing
                    })
                    .when(MethodCall.class, (methodCall, env) -> {
                        // do nothing
                    });


    private static Handle bsm(String name, Class<?> returnType, Class<?>... parameterTypes) {
        return new Handle(H_INVOKESTATIC,
                RT_NAME, name,
                MethodType.methodType(returnType, parameterTypes).toMethodDescriptorString(), false);
    }

    private static final String JSOBJECT = JSObject.class.getName().replace('.', '/');
    private static final String RT_NAME = RT.class.getName().replace('.', '/');
    private static final Handle BSM_UNDEFINED = bsm("bsm_undefined", Object.class, Lookup.class, String.class, Class.class);
    private static final Handle BSM_CONST = bsm("bsm_const", Object.class, Lookup.class, String.class, Class.class, int.class);
    private static final Handle BSM_FUNCALL = bsm("bsm_funcall", CallSite.class, Lookup.class, String.class, MethodType.class);
    private static final Handle BSM_LOOKUP = bsm("bsm_lookup", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_FUN = bsm("bsm_fun", Object.class, Lookup.class, String.class, Class.class, int.class);
    private static final Handle BSM_REGISTER = bsm("bsm_register", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_TRUTH = bsm("bsm_truth", CallSite.class, Lookup.class, String.class, MethodType.class);
    private static final Handle BSM_GET = bsm("bsm_get", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_SET = bsm("bsm_set", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_METHODCALL = bsm("bsm_methodcall", CallSite.class, Lookup.class, String.class, MethodType.class);

    private static VoidVisitor<JSObject> createVisitor(MethodVisitor mv, FunDictionary dictionary) {
    	  var visitor= new VoidVisitor<JSObject>();
        visitor
                .when(Block.class, (block, env) -> {
                    // for each expression, visit them and POP if it's not an instruction
                    for(var expr : block.instrs()) {
                        var start = new Label();
                        mv.visitLabel(start);
                        mv.visitLineNumber(block.lineNumber(), start);
                        visitor.visit(expr, env);
                        if(!(expr instanceof Instr)) {
                            mv.visitInsn(POP);
                        }
                    }
                })
                .when(Literal.class, (literal, env) -> {
                    // get the literal value, and use visitLDCInsn
                    var valueLit = literal.value();
                    // if it's an Integer, wrap it into a ConstantDynamic because the JVM doesn't have a primitive for boxed integer
                    if(valueLit instanceof Integer value) {
                        mv.visitLdcInsn(new ConstantDynamic("osef", "Ljava/lang/Integer;", BSM_CONST, value));
                    } else {
                        mv.visitLdcInsn((String) valueLit);
                    }
                })
                .when(FunCall.class, (funCall, env) -> {
                  // visit the qualifier
                    visitor.visit(funCall.qualifier(), env);
                  // load "this" = undefined
                    mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
                  // for each arguments, visit it
                    for(var arg : funCall.args()) {
                        visitor.visit(arg, env);
                    }
                  // the name of the invokedynamic is either "builtincall" or "funcall"
                  var name = (funCall.qualifier() instanceof LocalVarAccess localVar &&
                          env.lookup(localVar.name()) == JSObject.UNDEFINED) ? "builtincall": "funcall";
                  // generate an invokedynamic with the right name
                    var descriptor = genericMethodType(funCall.args().size() + 2).toMethodDescriptorString();
                   mv.visitInvokeDynamicInsn(name, descriptor, BSM_FUNCALL);
                })
                .when(LocalVarAssignment.class, (localVarAssignment, env) -> {
                  // visit expression
                    visitor.visit(localVarAssignment.expr(), env);
                  // store at the local var slot using a lookup from the name
                    var slotOrUndefined = env.lookup(localVarAssignment.name());
                    if(slotOrUndefined == JSObject.UNDEFINED) {
                        throw new Failure("no local variable " + localVarAssignment.name());
                    }
                    mv.visitVarInsn(ASTORE, (int)slotOrUndefined);
                })
                .when(LocalVarAccess.class, (localVarAccess, env) -> {
                    // get the name
                    var name = localVarAccess.name();
                    // lookup to find if its a local var access or a lookup access
                    var objectOrSlot = env.lookup(name);
                    if (objectOrSlot == JSObject.UNDEFINED) {
                        //  generate an invokedynamic doing a lookup
                        mv.visitInvokeDynamicInsn("osef", "()Ljava/lang/Object;", BSM_LOOKUP, name);
                   } else {
                        //  load the local variable at the slot
                        var slotOrUndefined = env.lookup(localVarAccess.name());
                        if(slotOrUndefined == JSObject.UNDEFINED) {
                            throw new Failure("no local variable " + localVarAccess.name());
                        }
                        mv.visitVarInsn(ALOAD, (int)slotOrUndefined);
                  }
                })
                .when(Fun.class, (fun, env) -> {
                  // register the fun inside the fun directory and get the corresponding id
                  var funId = dictionary.register(fun);
                  // emit a LDC to load the function corresponding to the if at runtime
                    mv.visitLdcInsn(new ConstantDynamic("fun", "Ljava/lang/Object;", BSM_FUN, funId));
                  fun.name().ifPresent(funName -> {
                    mv.visitInsn(DUP);
                    // generate an invokedynamic doing a register with the function name
                      mv.visitInvokeDynamicInsn("register", "(Ljava/lang/Object;)V", BSM_REGISTER, funName);
                  });
                })
                .when(Return.class, (_return, env) -> {
                    // visit the return expression
                    visitor.visit(_return.expr(), env);
                    // generate a RETURN
                    mv.visitInsn(ARETURN);
                })
                .when(If.class, (_if, env) -> {
                    var falseLabel = new Label();
                    var endLabel = new Label();
                    // visit the condition
                    visitor.visit(_if.condition(), env);
                    // generate an invokedynamic to transform an Object to a boolean using BSM_TRUTH
                    mv.visitInvokeDynamicInsn("if", "(Ljava/lang/Object;)Z", BSM_TRUTH);
                    mv.visitJumpInsn(IFEQ, falseLabel);
                    // visit the true block
                    visitor.visit(_if.trueBlock(), env);
                    mv.visitJumpInsn(GOTO, endLabel);
                    mv.visitLabel(falseLabel);
                    // visit the false block
                    visitor.visit(_if.falseBlock(), env);
                    mv.visitLabel(endLabel);
                })
                .when(New.class, (_new, env) -> {
                    throw new UnsupportedOperationException("TODO New");
                    // call newObject
                    //mv.visitInsn(ACONST_NULL);
                    //mv.visitMethodInsn(INVOKESTATIC, JSOBJECT, "newObject", "(L" + JSOBJECT + ";)L" + JSOBJECT + ';', false);
                    // for each initialization expression
                    //_new.initMap().forEach((key, init) -> {
                      //mv.visitInsn(DUP);
                      // generate a string with the key
                      // visit the initialization expression
                      // call register on the JSObject
                      //mv.visitMethodInsn(INVOKEVIRTUAL, JSOBJECT, "register", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
                    //});
                })
                .when(FieldAccess.class, (fieldAccess, env) -> {
                    throw new UnsupportedOperationException("TODO FieldAccess");
                    // visit the receiver
                    // generate an invokedynamic that goes a get through BSM_GET
                })
                .when(FieldAssignment.class, (fieldAssignment, env) -> {
                    throw new UnsupportedOperationException("TODO FieldAssignment");
                    // visit the receiver
                    // visit the expression
                    // generate an invokedynamic that goes a set through BSM_SET
                })
                .when(MethodCall.class, (methodCall, env) -> {
                    throw new UnsupportedOperationException("TODO MethodCall");
                    // visit the receiver
                    // get all arguments
                    //var args = methodCall.args();
                    // for each argument
                    //for (var expr : args) {
                      // visit it
                    //}
                    // generate an invokedynamic that call BSM_METHODCALL
                })
        ;
        return visitor;
    }
}
