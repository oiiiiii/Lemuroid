package com.swordfish.lemuroid.lib.library.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.swordfish.lemuroid.lib.library.db.entity.Game
import timber.log.Timber

class GameSearchDao(private val internalDao: Internal) {
    object CALLBACK : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            MIGRATION_7_8.migrate(db)
        }
    }

    object MIGRATION_7_8 : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE VIRTUAL TABLE fts_games USING FTS4(
                  tokenize=unicode61 "remove_diacritics=1",
                  content="games",
                  title);
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bu BEFORE UPDATE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bd BEFORE DELETE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_au AFTER UPDATE ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_ai AFTER INSERT ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                INSERT INTO fts_games(docid, title) SELECT id, title FROM games;
                """,
            )
        }
    }

    object MIGRATION_9_10 : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS fts_games")
            database.execSQL("DROP TRIGGER IF EXISTS games_bu")
            database.execSQL("DROP TRIGGER IF EXISTS games_bd")
            database.execSQL("DROP TRIGGER IF EXISTS games_au")
            database.execSQL("DROP TRIGGER IF EXISTS games_ai")

            database.execSQL(
                """
                CREATE VIRTUAL TABLE fts_games USING FTS4(
                  tokenize=unicode61 "remove_diacritics=1,tokenchars=[]",
                  content="games",
                  title,
                  fileName);
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bu BEFORE UPDATE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bd BEFORE DELETE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_au AFTER UPDATE ON games BEGIN
                  INSERT INTO fts_games(docid, title, fileName) VALUES(new.id, new.title, new.fileName);
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_ai AFTER INSERT ON games BEGIN
                  INSERT INTO fts_games(docid, title, fileName) VALUES(new.id, new.title, new.fileName);
                END;
                """,
            )
            database.execSQL(
                """
                INSERT INTO fts_games(docid, title, fileName) SELECT id, title, fileName FROM games;
                """,
            )
        }
    }

    fun search(query: String): PagingSource<Int, Game> {
        Timber.d("GameSearchDao.search() called with query: '$query'")
        return if (query.isNotEmpty()) {
            // 统一使用 LIKE 查询，同时支持中英文
            Timber.d("Using LIKE query: '%$query%'")
            internalDao.rawSearch(
                SimpleSQLiteQuery(
                    """
                    SELECT games.*
                        FROM games
                        WHERE title LIKE ? OR fileName LIKE ?
                        ORDER BY 
                            CASE 
                                WHEN fileName LIKE ? THEN 0
                                ELSE 1
                            END,
                            title ASC
                    """,
                    arrayOf("%$query%", "%$query%", "%$query%"),
                ),
            )
        } else {
            Timber.d("Empty query, returning all games")
            internalDao.rawSearch(
                SimpleSQLiteQuery(
                    """
                    SELECT games.*
                        FROM games
                        ORDER BY title ASC
                    """
                ),
            )
        }
    }

    private fun Char.isChinese(): Boolean {
        return this.toInt() in 0x4E00..0x9FA5
    }

    @Dao
    interface Internal {
        @RawQuery(observedEntities = [(Game::class)])
        fun rawSearch(query: SupportSQLiteQuery): PagingSource<Int, Game>
    }
}
