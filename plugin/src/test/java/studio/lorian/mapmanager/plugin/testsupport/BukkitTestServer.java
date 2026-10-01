package studio.lorian.mapmanager.plugin.testsupport;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/** Installs an isolated Bukkit facade and restores the previous server after each test. */
public final class BukkitTestServer implements AutoCloseable {
    private final Field field;
    private final Object previous;

    public BukkitTestServer(InvocationHandler handler) throws ReflectiveOperationException {
        field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previous = field.get(null);
        field.set(null, proxy(Server.class, handler));
    }

    public static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "equals" -> object == args[0];
                        case "hashCode" -> System.identityHashCode(object);
                        case "toString" -> "Test " + type.getSimpleName();
                        default -> throw new AssertionError(method);
                    };
                    return handler.invoke(object, method, args);
                }));
    }

    @Override public void close() throws IllegalAccessException { field.set(null, previous); }
}
