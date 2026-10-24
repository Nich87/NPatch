package top.nkbe.npatch.manager

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.service.IXposedService
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import top.nkbe.npatch.share.LSPConfig

class FrameworkIdentityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun managerAndEmbeddedServicesReportTheSameFramework() {
        val manager = XposedServiceBinder(context.packageName, context.applicationInfo.uid)
        val embedded = loader().loadClass("top.nkbe.npatch.service.EmbeddedXposedService")
            .getConstructor(Context::class.java, String::class.java, String::class.java)
            .newInstance(context, context.packageName, context.packageName) as IXposedService

        assertEquals("NPatch for Knot", LSPConfig.FRAMEWORK_NAME)
        assertEquals(LSPConfig.FRAMEWORK_NAME, manager.getFrameworkName())
        assertEquals(LSPConfig.FRAMEWORK_NAME, embedded.getFrameworkName())
    }

    @Test fun packagedModernApiReportsTheSameFrameworkAsTheManager() {
        val loader = loader()
        val serviceType = loader.loadClass("org.matrix.vector.ipc.IModuleService")
        val service = Proxy.newProxyInstance(loader, arrayOf(serviceType)) { _, method, _ ->
            error("Reading framework identity must not call the module service: ${method.name}")
        }
        val modeType = loader.loadClass("io.github.libxposed.api.XposedInterface\$ExceptionMode")
        val mode = requireNotNull(modeType.enumConstants).first { (it as Enum<*>).name == "PROTECTIVE" }
        val contextType = loader.loadClass("org.matrix.vector.impl.VectorContext")
        val modern = contextType
            .getConstructor(String::class.java, ApplicationInfo::class.java, serviceType, modeType)
            .newInstance(context.packageName, context.applicationInfo, service, mode)

        assertEquals("NPatch for Knot", contextType.getMethod("getFrameworkName").invoke(modern))
    }

    private fun loader(): ClassLoader {
        val dex = context.assets.open("npatch/loader.bin").use { it.readBytes() }
        return InMemoryDexClassLoader(ByteBuffer.wrap(dex), context.classLoader)
    }
}
