/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app

import android.app.Activity
import android.view.View
import androidx.lifecycle.Observer
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.util.HumanReadables
import androidx.test.espresso.util.TreeIterables
import androidx.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import org.hamcrest.StringDescription
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.crypto.store.PrivateKeysInfo
import java.util.concurrent.TimeoutException

object EspressoHelper {
    fun getCurrentActivity(): Activity? {
        var currentActivity: Activity? = null
        getInstrumentation().runOnMainSync {
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).elementAtOrNull(0)
        }
        return currentActivity
    }
}

fun waitForView(viewMatcher: Matcher<View>, timeout: Long = 10_000, waitForDisplayed: Boolean = true): ViewAction {
    return object : ViewAction {
        override fun getConstraints(): Matcher<View> {
            return Matchers.any(View::class.java)
        }

        override fun getDescription(): String {
            val matcherDescription = StringDescription()
            viewMatcher.describeTo(matcherDescription)
            return "wait for a specific view <$matcherDescription> to be ${if (waitForDisplayed) "displayed" else "not displayed during $timeout millis."}"
        }

        override fun perform(uiController: UiController, view: View) {
            println("*** waitForView 1 $view")
            uiController.loopMainThreadUntilIdle()
            val startTime = System.currentTimeMillis()
            val endTime = startTime + timeout
            val visibleMatcher = isDisplayed()

            do {
                println("*** waitForView loop $view end:$endTime current:${System.currentTimeMillis()}")
                val viewVisible = TreeIterables.breadthFirstViewTraversal(view)
                        .any { viewMatcher.matches(it) && visibleMatcher.matches(it) }

                println("*** waitForView loop viewVisible:$viewVisible")
                if (viewVisible == waitForDisplayed) return
                println("*** waitForView loop loopMainThreadForAtLeast...")
                uiController.loopMainThreadForAtLeast(50)
                println("*** waitForView loop ...loopMainThreadForAtLeast")
            } while (System.currentTimeMillis() < endTime)

            println("*** waitForView timeout $view")
            // Timeout happens.
            throw PerformException.Builder()
                    .withActionDescription(this.description)
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(TimeoutException())
                    .build()
        }
    }
}

fun initialSyncIdlingResource(session: Session): IdlingResource {
    val res = object : IdlingResource, Observer<SyncState> {
        private var callback: IdlingResource.ResourceCallback? = null

        override fun getName() = "InitialSyncIdlingResource for ${session.myUserId}"

        override fun isIdleNow(): Boolean {
            val isIdle = session.hasAlreadySynced()
            return isIdle
        }

        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
            this.callback = callback
        }

        override fun onChanged(t: SyncState?) {
            val isIdle = session.hasAlreadySynced()
            if (isIdle) {
                callback?.onTransitionToIdle()
                session.getSyncStateLive().removeObserver(this)
            }
        }
    }

    runOnUiThread {
        session.getSyncStateLive().observeForever(res)
    }

    return res
}

fun activityIdlingResource(activityClass: Class<*>): IdlingResource {
    val res = object : IdlingResource, ActivityLifecycleCallback {
        private var callback: IdlingResource.ResourceCallback? = null

        var hasResumed = false
        private var currentActivity: Activity? = null

        val uniqTS = System.currentTimeMillis()
        override fun getName() = "activityIdlingResource_${activityClass.name}_$uniqTS"

        override fun isIdleNow(): Boolean {
            val currentActivity = currentActivity ?: ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).elementAtOrNull(0)

            val isIdle = hasResumed || currentActivity?.javaClass?.let { activityClass.isAssignableFrom(it) } ?: false
            println("*** [$name] isIdleNow activityIdlingResource $currentActivity  isIdle:$isIdle")
            return isIdle
        }

        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
            println("*** [$name]  registerIdleTransitionCallback $callback")
            this.callback = callback
            // if (hasResumed) callback?.onTransitionToIdle()
        }

        override fun onActivityLifecycleChanged(activity: Activity?, stage: Stage?) {
            println("*** [$name]  onActivityLifecycleChanged $activity  $stage")
            currentActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).elementAtOrNull(0)
            val isIdle = currentActivity?.javaClass?.let { activityClass.isAssignableFrom(it) } ?: false
            println("*** [$name]  onActivityLifecycleChanged $currentActivity  isIdle:$isIdle")
            if (isIdle) {
                hasResumed = true
                println("*** [$name]  onActivityLifecycleChanged callback: $callback")
                callback?.onTransitionToIdle()
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(this)
            }
        }
    }
    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(res)
    return res
}

fun withIdlingResource(idlingResource: IdlingResource, block: (() -> Unit)) {
    println("*** withIdlingResource register")
    IdlingRegistry.getInstance().register(idlingResource)
    block.invoke()
    println("*** withIdlingResource unregister")
    IdlingRegistry.getInstance().unregister(idlingResource)
}

fun allSecretsKnownIdling(session: Session): IdlingResource {
    val res = object : IdlingResource, Observer<Optional<PrivateKeysInfo>> {
        private var callback: IdlingResource.ResourceCallback? = null

        var privateKeysInfo: PrivateKeysInfo? = session.cryptoService().crossSigningService().getCrossSigningPrivateKeys()
        override fun getName() = "AllSecretsKnownIdling_${session.myUserId}"

        override fun isIdleNow(): Boolean {
            println("*** [$name]/isIdleNow  allSecretsKnownIdling ${privateKeysInfo?.allKnown()}")
            return privateKeysInfo?.allKnown() == true
        }

        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
            this.callback = callback
        }

        override fun onChanged(t: Optional<PrivateKeysInfo>?) {
            println("*** [$name]  allSecretsKnownIdling ${t?.getOrNull()}")
            privateKeysInfo = t?.getOrNull()
            if (t?.getOrNull()?.allKnown() == true) {
                session.cryptoService().crossSigningService().getLiveCrossSigningPrivateKeys().removeObserver(this)
                callback?.onTransitionToIdle()
            }
        }
    }

    runOnUiThread {
        session.cryptoService().crossSigningService().getLiveCrossSigningPrivateKeys().observeForever(res)
    }

    return res
}
