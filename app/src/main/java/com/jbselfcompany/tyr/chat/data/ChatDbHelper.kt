package com.jbselfcompany.tyr.chat.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "tyr_chat.db"
        const val DB_VERSION = 6

        const val TABLE_CONTACTS = "contacts"
        const val COL_ID = "id"
        const val COL_ADDRESS = "address"
        const val COL_NAME = "name"
        const val COL_ADDED_AT = "added_at"
        const val COL_IS_PENDING = "is_pending"
        const val COL_IS_DECLINED = "is_declined"

        const val TABLE_MESSAGES = "messages"
        const val COL_IMAP_UID = "imap_uid"
        const val COL_FROM_ADDR = "from_addr"
        const val COL_TO_ADDR = "to_addr"
        const val COL_BODY = "body"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_READ = "is_read"
        const val COL_IS_SENT = "is_sent"
        const val COL_STATUS = "status"

        // Attachment columns
        const val COL_ATTACHMENT_PATH = "attachment_path"
        const val COL_ATTACHMENT_NAME = "attachment_name"
        const val COL_ATTACHMENT_MIME = "attachment_mime"
        const val COL_ATTACHMENT_SIZE = "attachment_size"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CONTACTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ADDRESS TEXT UNIQUE NOT NULL,
                $COL_NAME TEXT NOT NULL DEFAULT '',
                $COL_ADDED_AT INTEGER NOT NULL,
                $COL_IS_PENDING INTEGER NOT NULL DEFAULT 0,
                $COL_IS_DECLINED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_IMAP_UID INTEGER NOT NULL DEFAULT -1,
                $COL_FROM_ADDR TEXT NOT NULL,
                $COL_TO_ADDR TEXT NOT NULL,
                $COL_BODY TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_READ INTEGER NOT NULL DEFAULT 0,
                $COL_IS_SENT INTEGER NOT NULL DEFAULT 0,
                $COL_STATUS INTEGER NOT NULL DEFAULT ${ChatMessage.STATUS_SENT},
                $COL_ATTACHMENT_PATH TEXT,
                $COL_ATTACHMENT_NAME TEXT,
                $COL_ATTACHMENT_MIME TEXT,
                $COL_ATTACHMENT_SIZE INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Indexes for frequent conversation and unread-count queries
        db.execSQL(
            "CREATE INDEX idx_msg_conversation ON $TABLE_MESSAGES ($COL_FROM_ADDR, $COL_TO_ADDR)"
        )
        db.execSQL("CREATE INDEX idx_msg_timestamp ON $TABLE_MESSAGES ($COL_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_msg_imap_uid ON $TABLE_MESSAGES ($COL_IMAP_UID)")
        db.execSQL("CREATE INDEX idx_msg_is_read ON $TABLE_MESSAGES ($COL_IS_READ)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_STATUS INTEGER NOT NULL DEFAULT ${ChatMessage.STATUS_SENT}"
            )
        }
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE $TABLE_CONTACTS ADD COLUMN $COL_IS_PENDING INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                "ALTER TABLE $TABLE_CONTACTS ADD COLUMN $COL_IS_DECLINED INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_ATTACHMENT_PATH TEXT")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_ATTACHMENT_NAME TEXT")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_ATTACHMENT_MIME TEXT")
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_ATTACHMENT_SIZE INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 6) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_msg_conversation ON $TABLE_MESSAGES ($COL_FROM_ADDR, $COL_TO_ADDR)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_msg_timestamp ON $TABLE_MESSAGES ($COL_TIMESTAMP DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_msg_imap_uid ON $TABLE_MESSAGES ($COL_IMAP_UID)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_msg_is_read ON $TABLE_MESSAGES ($COL_IS_READ)"
            )
        }
    }
}
