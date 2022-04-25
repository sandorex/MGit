package com.manichord.mgit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.sheimi.sgit.R
import me.sheimi.sgit.database.RepoDbManager
import me.sheimi.sgit.database.models.Repo
import me.sheimi.sgit.repo.tasks.SheimiAsyncTask
import me.sheimi.sgit.repo.tasks.repo.*

class Receiver : BroadcastReceiver() {
    private val notificationChannel = "com.manichord.mgit.Receiver"
    private val notificationId = 0 // what to put here?
    private lateinit var notification: NotificationCompat.Builder

    class Callback(val context: Context, val receiver: Receiver) : SheimiAsyncTask.AsyncTaskCallback,
        SheimiAsyncTask.AsyncTaskPostCallback {

        override fun doInBackground(vararg params: Void?): Boolean {
            return true
        }

        override fun onPreExecute() {}

        override fun onProgressUpdate(vararg progress: String?) {
            this.receiver.notification
                .setContentText("${progress[0]} ${progress[2]}")
                .setProgress(100, progress[3]!!.toInt(), false)

            with(NotificationManagerCompat.from(context)) {
                notify(receiver.notificationId, receiver.notification.build())
            }
        }

        override fun onPostExecute(isSuccess: Boolean?) {
            if (isSuccess == true) {
                with(NotificationManagerCompat.from(context)) {
                    cancel(receiver.notificationId)
                }
            } else {
                // keep a notification if the task failed
                this.receiver.notification.setContentText("Task has failed")
                    .setStyle(NotificationCompat.BigTextStyle()
                        .bigText("TODO placeholder for information about the failure"))
                    .setOngoing(false)

                with(NotificationManagerCompat.from(context)) {
                    notify(receiver.notificationId, receiver.notification.build())
                }
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) {
            Log.d("mgit.Receiver", "intent and/or context are null")
            return
        }

        this.notification = NotificationCompat.Builder(context, this.notificationChannel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        val command = Command.values().firstOrNull {
            it.string.lowercase() == intent.getStringExtra(EXTRA_COMMAND)?.lowercase()
        } ?: Command.Invalid

        Log.d("mgit.Receiver", "got command: $command")

        var repo: Repo? = null
        val id = intent.getLongExtra(EXTRA_REPO_ID, 0)
        if (id > 0) {
            // TODO check if id is valid
            repo = Repo.getRepoById(id)
        }

        if (repo == null) {
            val localPath = intent.getStringExtra(EXTRA_REPO_LOCAL_PATH)
            if (localPath.isNullOrEmpty()) {
                // TODO proper error
                throw Exception("Invalid repository id and/or local path")
            }

            val cursor = RepoDbManager.queryAllRepo()
            val repos = Repo.getRepoList(context, cursor)
            repo = repos.firstOrNull { it.localPath == localPath }
                ?:
                // TODO proper error
                throw Exception("Invalid repository id and/or local path")
        }

        // set the notification title to the command name
        this.notification.setContentTitle("${repo.currentDisplayName}: ${command.string}")

        when (command) {
            Command.Pull, Command.Push -> {
                var remote = intent.getStringExtra(EXTRA_REMOTE)
                if (remote.isNullOrEmpty()) {
                    // default to first remote
                    remote = repo.remotes.firstOrNull()
                        ?:
                        // TODO proper error
                        throw Exception("Repository contains no remotes")
                }

                if (!repo.remotes.contains(remote)) {
                    // TODO proper error
                    throw Exception("Invalid remote")
                }

                when (command) {
                    Command.Push -> {
                        PushTask(repo,
                            remote,
                            intent.getBooleanExtra(EXTRA_PUSH_ALL, false),
                            intent.getBooleanExtra(EXTRA_FORCE, false),
                            Callback(context, this)).executeTask()
                    }
                    Command.Pull -> {
                        PullTask(repo,
                            remote,
                            intent.getBooleanExtra(EXTRA_FORCE, false),
                            Callback(context, this)).executeTask()
                    }
                    else -> {
                        throw RuntimeException("unreachable code")
                    }
                }
            }

            Command.Commit -> {
                // empty commit message is legal so only check for null
                val msg = intent.getStringExtra(EXTRA_COMMIT_MSG)
                    ?:
                    // TODO proper error
                    throw Exception("No commit message provided")

                this.notification.setContentText("Commiting changes")
                    .setOngoing(true)

                with(NotificationManagerCompat.from(context)) {
                    notify(notificationId, notification.build())
                }

                CommitChangesTask(repo,
                    msg,
                    intent.getBooleanExtra(EXTRA_AMEND, false),
                    intent.getBooleanExtra(EXTRA_STAGE_ALL, false),
                    intent.getStringExtra(EXTRA_AUTHOR_NAME),
                    intent.getStringExtra(EXTRA_AUTHOR_EMAIL),
                    Callback(context, this)).executeTask()
            }

            Command.Stage -> {
                val filePattern = intent.getStringExtra(EXTRA_FILE_PATTERN)
                if (filePattern.isNullOrEmpty()) {
                    // TODO proper error
                    throw Exception("Invalid file pattern")
                }

                AddToStageTask(repo, filePattern).executeTask()
            }

            Command.Checkout -> {
                val commit = intent.getStringExtra(EXTRA_COMMIT)
                val branch = intent.getStringExtra(EXTRA_BRANCH)

                if (commit.isNullOrBlank() && branch.isNullOrBlank()) {
                    // TODO proper error
                    throw Exception("Neither commit nor branch was provided for checkout command")
                }

                this.notification.setContentText("???") // TODO
                    .setOngoing(true)

                with(NotificationManagerCompat.from(context)) {
                    notify(notificationId, notification.build())
                }

                CheckoutTask(repo,
                    commit,
                    branch,
                    Callback(context, this)).executeTask()
            }

            else -> {
                // TODO proper error
                throw Exception("Invalid command")
            }
        }
    }

     companion object {
         // command to run, for list of commands look at Command enum
         const val EXTRA_COMMAND = "command"

         // local path of the repository starting from the root directory set in MGit (string)
         const val EXTRA_REPO_LOCAL_PATH = "local_path"

         // id of the repository, starting from 1 (long)
         const val EXTRA_REPO_ID = "id"

         // remote to push to, defaults to origin (string)
         const val EXTRA_REMOTE = "remote"

         // used for both push and pull (string)
         const val EXTRA_FORCE = "force"

         const val EXTRA_PUSH_ALL = "push_all"
         const val EXTRA_STAGE_ALL = "stage_all"
         const val EXTRA_AMEND = "amend"
         const val EXTRA_COMMIT_MSG = "commit_msg"
         const val EXTRA_AUTHOR_NAME = "author_name"
         const val EXTRA_AUTHOR_EMAIL = "author_email"
         const val EXTRA_FILE_PATTERN = "file_pattern"
         const val EXTRA_BRANCH = "branch"
         const val EXTRA_COMMIT = "commit"

         enum class Command(val string: String) {
             Invalid("Invalid"),
             Push("Push"),
             Pull("Pull"),
             Stage("Stage"),
             Commit("Commit"),
             Checkout("Checkout"),
         }
     }
}
