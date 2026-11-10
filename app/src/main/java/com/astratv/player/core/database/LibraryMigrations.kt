package com.astratv.player.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object: Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN sortName TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE items SET sortName=lower(name)")
        db.execSQL("DROP INDEX IF EXISTS index_items_kind_name_id")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_items_kind_sortName_id ON items(kind,sortName,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_items_kind_groupName_sortName_id ON items(kind,groupName,sortName,id)")
    }
}
