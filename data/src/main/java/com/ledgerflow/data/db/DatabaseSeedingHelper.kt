package com.ledgerflow.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

object DatabaseSeedingHelper {

    fun seedDatabase(db: SupportSQLiteDatabase) {
        Timber.d("DatabaseSeedingHelper: Seeding fresh database defaults.")
        
        // 1. Default Categories
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (1, 'Food', 0, '#FF9800', '🍔', 0, 1)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (2, 'Transport', 0, '#2196F3', '🚗', 1, 1)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (3, 'Shopping', 0, '#E91E63', '🛍️', 2, 1)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (4, 'Bills', 0, '#4CAF50', '💡', 3, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (5, 'Entertainment', 0, '#9C27B0', '🎬', 4, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (6, 'Healthcare', 0, '#F44336', '🏥', 5, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (7, 'Education', 0, '#3F51B5', '🎓', 6, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (8, 'Travel', 0, '#00BCD4', '✈️', 7, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (9, 'Groceries', 0, '#8BC34A', '🛒', 8, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (10, 'Subscriptions', 0, '#FFC107', '💳', 9, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (11, 'Investment', 0, '#009688', '📈', 10, 0)")
        db.execSQL("INSERT OR IGNORE INTO categories (id, name, is_archived, color, icon, display_order, is_pinned) VALUES (12, 'Others', 0, '#9E9E9E', '📁', 11, 0)")
        
        // 2. Default Subcategories
        // Food subcategories
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (1, 1, 'Restaurants', '#FF9800', '🍔', 0, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (2, 1, 'Cafes', '#FF9800', '☕', 1, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (3, 1, 'Fast Food', '#FF9800', '🍕', 2, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (4, 1, 'Delivery', '#FF9800', '🛵', 3, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (5, 1, 'Snacks', '#FF9800', '🍿', 4, 0, 0)")

        // Transport subcategories
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (6, 2, 'Fuel', '#2196F3', '⛽', 0, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (7, 2, 'Metro', '#2196F3', '🚇', 1, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (8, 2, 'Taxi', '#2196F3', '🚕', 2, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (9, 2, 'Bus', '#2196F3', '🚌', 3, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (10, 2, 'Parking', '#2196F3', '🅿️', 4, 0, 0)")

        // Shopping subcategories
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (11, 3, 'Clothing', '#E91E63', '👕', 0, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (12, 3, 'Electronics', '#E91E63', '💻', 1, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (13, 3, 'Home', '#E91E63', '🏠', 2, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (14, 3, 'Accessories', '#E91E63', '⌚', 3, 0, 0)")

        // Bills subcategories
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (15, 4, 'Electricity', '#4CAF50', '⚡', 0, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (16, 4, 'Internet', '#4CAF50', '🌐', 1, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (17, 4, 'Mobile', '#4CAF50', '📱', 2, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (18, 4, 'Water', '#4CAF50', '🚰', 3, 0, 0)")
        db.execSQL("INSERT OR IGNORE INTO subcategories (id, category_id, name, color, icon, display_order, is_archived, is_pinned) VALUES (19, 4, 'Gas', '#4CAF50', '🔥', 4, 0, 0)")

        // 3. Default Payment Methods
        db.execSQL("INSERT OR IGNORE INTO payment_methods (id, name, type) VALUES (1, 'Cash', 'CASH')")
        db.execSQL("INSERT OR IGNORE INTO payment_methods (id, name, type) VALUES (2, 'UPI / Bank', 'BANK_ACCOUNT')")
        db.execSQL("INSERT OR IGNORE INTO payment_methods (id, name, type) VALUES (3, 'Credit Card', 'CREDIT_CARD')")
        
        // 4. Default Merchants
        db.execSQL("INSERT OR IGNORE INTO merchants (id, display_name, normalized_name, is_archived, default_category_id, notes) VALUES (1, 'General Merchant', 'general merchant', 0, NULL, 'Default merchant')")
    }
}
