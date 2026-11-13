import com.intellij.ide.plugins.PluginDescriptorLoader;
import com.intellij.ide.plugins.PluginMainDescriptor;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

public class Probe {
    public static void main(String[] a) throws Exception {
        Path p = Paths.get(a[0]);
        System.out.println("探测: " + p.getFileName());
        PluginMainDescriptor d;
        try {
            d = PluginDescriptorLoader.loadDescriptorFromArtifact(p, null);
        } catch (Throwable t) {
            System.out.println("异常: " + t.getClass().getName() + ": " + t.getMessage());
            return;
        }
        if (d == null) {
            System.out.println("结果: null  <== 平台拒绝加载");
            return;
        }
        System.out.println("结果: 成功  id=" + d.getPluginId() + "  name=" + d.getName() + "  version=" + d.getVersion());

        // 反射读取动作与扩展数量，确认 XML 内容真被解析
        for (String getter : new String[]{"getActions", "getExtensions", "getExtensionPoints", "getDependsList", "getIncompatibilities"}) {
            try {
                Method m = d.getClass().getMethod(getter);
                Object v = m.invoke(d);
                int n = -1;
                if (v instanceof Collection) n = ((Collection<?>) v).size();
                else if (v instanceof Map) n = ((Map<?, ?>) v).size();
                else if (v != null && v.getClass().isArray()) n = java.lang.reflect.Array.getLength(v);
                System.out.println("  " + getter + " -> " + (n >= 0 ? n + " 项" : String.valueOf(v)));
            } catch (NoSuchMethodException e) {
                // 该版本没有此 getter，跳过
            }
        }
    }
}
