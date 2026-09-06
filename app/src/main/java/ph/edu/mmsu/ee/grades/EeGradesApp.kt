package ph.edu.mmsu.ee.grades

import android.app.Application
import android.content.Context
import ph.edu.mmsu.ee.grades.data.ApiClient
import ph.edu.mmsu.ee.grades.data.Repository
import ph.edu.mmsu.ee.grades.data.Store

class EeGradesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

/**
 * Deliberately plain wiring. There is one repository and it has no variants,
 * so a dependency-injection framework would be more machinery than the app
 * earns. View models read [repo] directly, which keeps their constructors
 * empty and lets Compose create them with the default factory.
 */
object ServiceLocator {

    @Volatile
    private var repository: Repository? = null

    fun init(context: Context) {
        if (repository != null) return
        synchronized(this) {
            if (repository != null) return
            repository = Repository(
                api = ApiClient(BuildConfig.API_URL),
                store = Store(context.applicationContext)
            )
        }
    }

    val repo: Repository
        get() = repository ?: error("ServiceLocator.init() was not called from Application.onCreate()")
}
