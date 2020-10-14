package fr.umlv.smalljs.astinterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.stream.Collectors;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.ast.Visitor;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public class ASTInterpreter {
    private static <T> T as(Object value, Class<T> type, Expr failedExpr) {
        try {
            return type.cast(value);
        } catch(@SuppressWarnings("unused") ClassCastException e) {
            throw new Failure("at line " + failedExpr.lineNumber() + ", type error " + value + " is not a " + type.getSimpleName());
        }
    }

    static Object visit(Expr expr, JSObject env) {
        return VISITOR.visit(expr, env);
    }

    private static final Visitor<JSObject, Object> VISITOR =
            new Visitor<JSObject, Object>()
                    .when(Block.class, (block, env) -> {
                        block.instrs().forEach(expr -> visit(expr, env));
                        return UNDEFINED;
                    })
                    .when(Literal.class, (literal, env) -> {
                        return literal.value();
                    })
                    .when(FunCall.class, (funCall, env) -> {
                        var funcName = visit(funCall.qualifier(), env);
                        var fn = as(funcName, JSObject.class, funCall);
                        return fn.invoke(UNDEFINED,
                            funCall.args().stream().map(expr -> visit(expr, env)).toArray());
                    })
                    .when(LocalVarAccess.class, (localVarAccess, env) -> {
                        return env.lookup(localVarAccess.name());
                    })
                    .when(LocalVarAssignment.class, (localVarAssignment, env) -> {
                        var name = localVarAssignment.name();
                        var value = visit(localVarAssignment.expr(), env);
                        var exist = env.lookup(name) != UNDEFINED;
                        /*if(localVarAssignment.declaration() && exist) {
                            throw new Failure("Variable already exist");
                        }*/
                        if(!localVarAssignment.declaration() && !exist) {
                            throw new Failure("Variable doesn't exist");
                        }
                        env.register(name, value);
                        return UNDEFINED;
                    })
                    .when(Fun.class, (fun, env) -> {
                        var hasName = fun.name().isPresent();
                        var name = hasName ? fun.name().get() : "lambda";

                        var fn = JSObject.newFunction(name, (self, receiver, args) -> {
                            if(args.length != fun.parameters().size()) {
                                throw new Failure("Not same amount of arguments at line " + fun.lineNumber());
                            }

                            var inEnv = JSObject.newEnv(env);
                            inEnv.register("this", receiver);
                            for(var i = 0; i < args.length; ++i) {
                                inEnv.register(fun.parameters().get(i), args[i]);
                            }
                            try {
                                return visit(fun.body(), inEnv);
                            } catch (ReturnError ret) {
                                return ret.getValue();
                            }
                        });
                        if(hasName) {
                            env.register(name, fn);
                        }
                        return fn;
                    })
                    .when(Return.class, (_return, env) -> {
                        throw new ReturnError(visit(_return.expr(), env));
                    })
                    .when(If.class, (_if, env) -> {
                        int cond = as(visit(_if.condition(), env), Integer.class, _if);
                        var block = cond != 0 ? _if.trueBlock() : _if.falseBlock();
                        visit(block, env);
                        return UNDEFINED;
                    })
                    .when(New.class, (_new, env) -> {
                        var obj = JSObject.newObject(null);
                        _new.initMap().forEach((s, expr) -> obj.register(s, visit(expr, env)));
                        return obj;
                    })
                    .when(FieldAccess.class, (fieldAccess, env) -> {
                        var parent = as(visit(fieldAccess.receiver(), env), JSObject.class, fieldAccess);
                        return parent.lookup(fieldAccess.name());
                    })
                    .when(FieldAssignment.class, (fieldAssignment, env) -> {
                        var parent = visit(fieldAssignment.receiver(), env);
                        var p = as(parent, JSObject.class, fieldAssignment);
                        p.register(fieldAssignment.name(), visit(fieldAssignment.expr(), env));
                        return UNDEFINED;
                    })
                    .when(MethodCall.class, (methodCall, env) -> {
                        var parent = as(visit(methodCall.receiver(), env), JSObject.class, methodCall);
                        var fn = as(parent.lookup(methodCall.name()), JSObject.class, methodCall);
                        return fn.invoke(parent,
                            methodCall.args().stream().map(expr -> visit(expr, env)).toArray());
                    })
            ;

    @SuppressWarnings("unchecked")
    public static void interpret(Script script, PrintStream outStream) {
        JSObject globalEnv = JSObject.newEnv(null);
        Block body = script.body();
        globalEnv.register("global", globalEnv);
        globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
            System.err.println("print called with " + Arrays.toString(args));
            outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
            return UNDEFINED;
        }));
        globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
        globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
        globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
        globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
        globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));

        globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
        globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
        globalEnv.register("<", JSObject.newFunction("<",   (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
        globalEnv.register("<=", JSObject.newFunction("<=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
        globalEnv.register(">", JSObject.newFunction(">",   (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
        globalEnv.register(">=", JSObject.newFunction(">=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
        visit(body, globalEnv);
    }
}

