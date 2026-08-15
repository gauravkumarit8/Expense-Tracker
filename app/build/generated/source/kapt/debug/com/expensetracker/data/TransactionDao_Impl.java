package com.expensetracker.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class TransactionDao_Impl implements TransactionDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Transaction> __insertionAdapterOfTransaction;

  private final SharedSQLiteStatement __preparedStmtOfDeleteAll;

  public TransactionDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfTransaction = new EntityInsertionAdapter<Transaction>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `transactions` (`id`,`amount`,`direction`,`merchantOrContact`,`bankOrSource`,`timestampMillis`,`category`,`rawTextHash`,`needsReview`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Transaction entity) {
        statement.bindLong(1, entity.getId());
        statement.bindDouble(2, entity.getAmount());
        statement.bindString(3, __Direction_enumToString(entity.getDirection()));
        if (entity.getMerchantOrContact() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getMerchantOrContact());
        }
        if (entity.getBankOrSource() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getBankOrSource());
        }
        statement.bindLong(6, entity.getTimestampMillis());
        if (entity.getCategory() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getCategory());
        }
        if (entity.getRawTextHash() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getRawTextHash());
        }
        final int _tmp = entity.getNeedsReview() ? 1 : 0;
        statement.bindLong(9, _tmp);
      }
    };
    this.__preparedStmtOfDeleteAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM transactions";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final Transaction transaction,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfTransaction.insertAndReturnId(transaction);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteAll.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<Transaction>> getAll() {
    final String _sql = "SELECT * FROM transactions ORDER BY timestampMillis DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"transactions"}, new Callable<List<Transaction>>() {
      @Override
      @NonNull
      public List<Transaction> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfAmount = CursorUtil.getColumnIndexOrThrow(_cursor, "amount");
          final int _cursorIndexOfDirection = CursorUtil.getColumnIndexOrThrow(_cursor, "direction");
          final int _cursorIndexOfMerchantOrContact = CursorUtil.getColumnIndexOrThrow(_cursor, "merchantOrContact");
          final int _cursorIndexOfBankOrSource = CursorUtil.getColumnIndexOrThrow(_cursor, "bankOrSource");
          final int _cursorIndexOfTimestampMillis = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMillis");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfRawTextHash = CursorUtil.getColumnIndexOrThrow(_cursor, "rawTextHash");
          final int _cursorIndexOfNeedsReview = CursorUtil.getColumnIndexOrThrow(_cursor, "needsReview");
          final List<Transaction> _result = new ArrayList<Transaction>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Transaction _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final double _tmpAmount;
            _tmpAmount = _cursor.getDouble(_cursorIndexOfAmount);
            final Direction _tmpDirection;
            _tmpDirection = __Direction_stringToEnum(_cursor.getString(_cursorIndexOfDirection));
            final String _tmpMerchantOrContact;
            if (_cursor.isNull(_cursorIndexOfMerchantOrContact)) {
              _tmpMerchantOrContact = null;
            } else {
              _tmpMerchantOrContact = _cursor.getString(_cursorIndexOfMerchantOrContact);
            }
            final String _tmpBankOrSource;
            if (_cursor.isNull(_cursorIndexOfBankOrSource)) {
              _tmpBankOrSource = null;
            } else {
              _tmpBankOrSource = _cursor.getString(_cursorIndexOfBankOrSource);
            }
            final long _tmpTimestampMillis;
            _tmpTimestampMillis = _cursor.getLong(_cursorIndexOfTimestampMillis);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final String _tmpRawTextHash;
            if (_cursor.isNull(_cursorIndexOfRawTextHash)) {
              _tmpRawTextHash = null;
            } else {
              _tmpRawTextHash = _cursor.getString(_cursorIndexOfRawTextHash);
            }
            final boolean _tmpNeedsReview;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfNeedsReview);
            _tmpNeedsReview = _tmp != 0;
            _item = new Transaction(_tmpId,_tmpAmount,_tmpDirection,_tmpMerchantOrContact,_tmpBankOrSource,_tmpTimestampMillis,_tmpCategory,_tmpRawTextHash,_tmpNeedsReview);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<Transaction>> getNeedsReview() {
    final String _sql = "SELECT * FROM transactions WHERE needsReview = 1 ORDER BY timestampMillis DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"transactions"}, new Callable<List<Transaction>>() {
      @Override
      @NonNull
      public List<Transaction> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfAmount = CursorUtil.getColumnIndexOrThrow(_cursor, "amount");
          final int _cursorIndexOfDirection = CursorUtil.getColumnIndexOrThrow(_cursor, "direction");
          final int _cursorIndexOfMerchantOrContact = CursorUtil.getColumnIndexOrThrow(_cursor, "merchantOrContact");
          final int _cursorIndexOfBankOrSource = CursorUtil.getColumnIndexOrThrow(_cursor, "bankOrSource");
          final int _cursorIndexOfTimestampMillis = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMillis");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfRawTextHash = CursorUtil.getColumnIndexOrThrow(_cursor, "rawTextHash");
          final int _cursorIndexOfNeedsReview = CursorUtil.getColumnIndexOrThrow(_cursor, "needsReview");
          final List<Transaction> _result = new ArrayList<Transaction>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Transaction _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final double _tmpAmount;
            _tmpAmount = _cursor.getDouble(_cursorIndexOfAmount);
            final Direction _tmpDirection;
            _tmpDirection = __Direction_stringToEnum(_cursor.getString(_cursorIndexOfDirection));
            final String _tmpMerchantOrContact;
            if (_cursor.isNull(_cursorIndexOfMerchantOrContact)) {
              _tmpMerchantOrContact = null;
            } else {
              _tmpMerchantOrContact = _cursor.getString(_cursorIndexOfMerchantOrContact);
            }
            final String _tmpBankOrSource;
            if (_cursor.isNull(_cursorIndexOfBankOrSource)) {
              _tmpBankOrSource = null;
            } else {
              _tmpBankOrSource = _cursor.getString(_cursorIndexOfBankOrSource);
            }
            final long _tmpTimestampMillis;
            _tmpTimestampMillis = _cursor.getLong(_cursorIndexOfTimestampMillis);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final String _tmpRawTextHash;
            if (_cursor.isNull(_cursorIndexOfRawTextHash)) {
              _tmpRawTextHash = null;
            } else {
              _tmpRawTextHash = _cursor.getString(_cursorIndexOfRawTextHash);
            }
            final boolean _tmpNeedsReview;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfNeedsReview);
            _tmpNeedsReview = _tmp != 0;
            _item = new Transaction(_tmpId,_tmpAmount,_tmpDirection,_tmpMerchantOrContact,_tmpBankOrSource,_tmpTimestampMillis,_tmpCategory,_tmpRawTextHash,_tmpNeedsReview);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object existsByHash(final String hash, final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM transactions WHERE rawTextHash = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (hash == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, hash);
    }
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final Integer _tmp;
            if (_cursor.isNull(0)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getInt(0);
            }
            _result = _tmp;
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }

  private String __Direction_enumToString(@NonNull final Direction _value) {
    switch (_value) {
      case SENT: return "SENT";
      case RECEIVED: return "RECEIVED";
      case UNKNOWN: return "UNKNOWN";
      default: throw new IllegalArgumentException("Can't convert enum to string, unknown enum value: " + _value);
    }
  }

  private Direction __Direction_stringToEnum(@NonNull final String _value) {
    switch (_value) {
      case "SENT": return Direction.SENT;
      case "RECEIVED": return Direction.RECEIVED;
      case "UNKNOWN": return Direction.UNKNOWN;
      default: throw new IllegalArgumentException("Can't convert value to enum, unknown value: " + _value);
    }
  }
}
