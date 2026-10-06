package top.nkbe.npatch.loader

import android.app.AppComponentFactory
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.InMemoryDexClassLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import top.nkbe.npatch.share.Constants
import top.nkbe.nza.dex.DexShimBuilder
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class MetaLoaderFactoryDelegationTest {
    @Test fun injectedFactoryDoesNotDelegateBackToItself() {
        val name = "test.npatch.delegation.InjectedFactory"
        val shim = DexShimBuilder.buildFactoryShim(name, Constants.PROXY_APP_COMPONENT_FACTORY)
        val factory = factory(name, shim)

        val application = factory.instantiateApplication(factory.javaClass.classLoader, Application::class.java.name)

        assertEquals(Application::class.java, application.javaClass)
    }

    @Test fun originalFactoryStillConstructsApplication() {
        val factory = factory(OriginalFactory::class.java.name)

        val application = factory.instantiateApplication(factory.javaClass.classLoader, Application::class.java.name)

        assertEquals(MarkerApplication::class.java, application.javaClass)
    }

    private fun factory(originalName: String, shim: ByteArray? = null): AppComponentFactory {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val meta = context.assets.open("npatch/metaloader.dex").use { it.readBytes() }
        val buffers = listOfNotNull(ByteBuffer.wrap(meta), shim?.let(ByteBuffer::wrap)).toTypedArray()
        val loader = InMemoryDexClassLoader(buffers, javaClass.classLoader)
        val stub = loader.loadClass(Constants.PROXY_APP_COMPONENT_FACTORY)
        val state = stub.getDeclaredField("bootstrapState").apply { isAccessible = true }
        state.set(null, state.type.enumConstants.single { (it as Enum<*>).name == "SKIPPED_APP_ZYGOTE" })
        stub.getDeclaredField("originalFactoryName").apply { isAccessible = true }.set(null, originalName)
        return stub.getDeclaredConstructor().newInstance() as AppComponentFactory
    }

    class MarkerApplication : Application()

    class OriginalFactory : AppComponentFactory() {
        override fun instantiateApplication(cl: ClassLoader, className: String): Application = MarkerApplication()
    }
}
