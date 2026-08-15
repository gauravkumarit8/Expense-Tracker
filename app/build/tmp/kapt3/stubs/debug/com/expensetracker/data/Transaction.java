package com.expensetracker.data;

/**
 * Structured, parsed transaction record.
 *
 * IMPORTANT (see REQUIREMENTS.md Security ยง2 - Data Minimization):
 * We deliberately do NOT store the raw SMS/notification text here.
 * Only the fields extracted by TransactionParser are persisted.
 * `rawTextHash` is kept only for de-duplication (avoid double-counting the
 * same message if both the notification listener and SMS receiver fire for
 * it) and is a one-way hash, not reversible to the original text.
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u00004\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0010\u0006\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010\u000b\n\u0002\b\u001c\n\u0002\u0010\b\n\u0002\b\u0002\b\u0087\b\u0018\u00002\u00020\u0001BW\u0012\b\b\u0002\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u0012\b\u0010\b\u001a\u0004\u0018\u00010\t\u0012\u0006\u0010\n\u001a\u00020\t\u0012\u0006\u0010\u000b\u001a\u00020\u0003\u0012\n\b\u0002\u0010\f\u001a\u0004\u0018\u00010\t\u0012\u0006\u0010\r\u001a\u00020\t\u0012\b\b\u0002\u0010\u000e\u001a\u00020\u000f\u00a2\u0006\u0002\u0010\u0010J\t\u0010\u001f\u001a\u00020\u0003H\u00c6\u0003J\t\u0010 \u001a\u00020\u0005H\u00c6\u0003J\t\u0010!\u001a\u00020\u0007H\u00c6\u0003J\u000b\u0010\"\u001a\u0004\u0018\u00010\tH\u00c6\u0003J\t\u0010#\u001a\u00020\tH\u00c6\u0003J\t\u0010$\u001a\u00020\u0003H\u00c6\u0003J\u000b\u0010%\u001a\u0004\u0018\u00010\tH\u00c6\u0003J\t\u0010&\u001a\u00020\tH\u00c6\u0003J\t\u0010\'\u001a\u00020\u000fH\u00c6\u0003Jg\u0010(\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00052\b\b\u0002\u0010\u0006\u001a\u00020\u00072\n\b\u0002\u0010\b\u001a\u0004\u0018\u00010\t2\b\b\u0002\u0010\n\u001a\u00020\t2\b\b\u0002\u0010\u000b\u001a\u00020\u00032\n\b\u0002\u0010\f\u001a\u0004\u0018\u00010\t2\b\b\u0002\u0010\r\u001a\u00020\t2\b\b\u0002\u0010\u000e\u001a\u00020\u000fH\u00c6\u0001J\u0013\u0010)\u001a\u00020\u000f2\b\u0010*\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u0010+\u001a\u00020,H\u00d6\u0001J\t\u0010-\u001a\u00020\tH\u00d6\u0001R\u0011\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0011\u0010\u0012R\u0011\u0010\n\u001a\u00020\t\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0013\u0010\u0014R\u0013\u0010\f\u001a\u0004\u0018\u00010\t\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0015\u0010\u0014R\u0011\u0010\u0006\u001a\u00020\u0007\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0016\u0010\u0017R\u0016\u0010\u0002\u001a\u00020\u00038\u0006X\u0087\u0004\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0018\u0010\u0019R\u0013\u0010\b\u001a\u0004\u0018\u00010\t\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001a\u0010\u0014R\u0011\u0010\u000e\u001a\u00020\u000f\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001b\u0010\u001cR\u0011\u0010\r\u001a\u00020\t\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001d\u0010\u0014R\u0011\u0010\u000b\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u001e\u0010\u0019\u00a8\u0006."}, d2 = {"Lcom/expensetracker/data/Transaction;", "", "id", "", "amount", "", "direction", "Lcom/expensetracker/data/Direction;", "merchantOrContact", "", "bankOrSource", "timestampMillis", "category", "rawTextHash", "needsReview", "", "(JDLcom/expensetracker/data/Direction;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;Z)V", "getAmount", "()D", "getBankOrSource", "()Ljava/lang/String;", "getCategory", "getDirection", "()Lcom/expensetracker/data/Direction;", "getId", "()J", "getMerchantOrContact", "getNeedsReview", "()Z", "getRawTextHash", "getTimestampMillis", "component1", "component2", "component3", "component4", "component5", "component6", "component7", "component8", "component9", "copy", "equals", "other", "hashCode", "", "toString", "app_debug"})
@androidx.room.Entity(tableName = "transactions")
public final class Transaction {
    @androidx.room.PrimaryKey(autoGenerate = true)
    private final long id = 0L;
    private final double amount = 0.0;
    @org.jetbrains.annotations.NotNull()
    private final com.expensetracker.data.Direction direction = null;
    @org.jetbrains.annotations.Nullable()
    private final java.lang.String merchantOrContact = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String bankOrSource = null;
    private final long timestampMillis = 0L;
    @org.jetbrains.annotations.Nullable()
    private final java.lang.String category = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String rawTextHash = null;
    private final boolean needsReview = false;
    
    public Transaction(long id, double amount, @org.jetbrains.annotations.NotNull()
    com.expensetracker.data.Direction direction, @org.jetbrains.annotations.Nullable()
    java.lang.String merchantOrContact, @org.jetbrains.annotations.NotNull()
    java.lang.String bankOrSource, long timestampMillis, @org.jetbrains.annotations.Nullable()
    java.lang.String category, @org.jetbrains.annotations.NotNull()
    java.lang.String rawTextHash, boolean needsReview) {
        super();
    }
    
    public final long getId() {
        return 0L;
    }
    
    public final double getAmount() {
        return 0.0;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final com.expensetracker.data.Direction getDirection() {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String getMerchantOrContact() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getBankOrSource() {
        return null;
    }
    
    public final long getTimestampMillis() {
        return 0L;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String getCategory() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getRawTextHash() {
        return null;
    }
    
    public final boolean getNeedsReview() {
        return false;
    }
    
    public final long component1() {
        return 0L;
    }
    
    public final double component2() {
        return 0.0;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final com.expensetracker.data.Direction component3() {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String component4() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component5() {
        return null;
    }
    
    public final long component6() {
        return 0L;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String component7() {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String component8() {
        return null;
    }
    
    public final boolean component9() {
        return false;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final com.expensetracker.data.Transaction copy(long id, double amount, @org.jetbrains.annotations.NotNull()
    com.expensetracker.data.Direction direction, @org.jetbrains.annotations.Nullable()
    java.lang.String merchantOrContact, @org.jetbrains.annotations.NotNull()
    java.lang.String bankOrSource, long timestampMillis, @org.jetbrains.annotations.Nullable()
    java.lang.String category, @org.jetbrains.annotations.NotNull()
    java.lang.String rawTextHash, boolean needsReview) {
        return null;
    }
    
    @java.lang.Override()
    public boolean equals(@org.jetbrains.annotations.Nullable()
    java.lang.Object other) {
        return false;
    }
    
    @java.lang.Override()
    public int hashCode() {
        return 0;
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.NotNull()
    public java.lang.String toString() {
        return null;
    }
}