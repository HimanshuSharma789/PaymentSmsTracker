# Payment SMS Tracker

Payment SMS Tracker is an Android application designed to help users automatically track their expenses by parsing incoming SMS messages from banks and payment services. It organizes these transactions, allows users to categorize them, add notes, and view a history of their spending.


## Screenshots

Here are a few screenshots showcasing the app's features:

- **Welcome Screen:**
[Welcome Screen](screenshots/welcome_screen.jpg)

- **Transaction List:**
[Transaction List View](screenshots/list_all_transaction.jpg)

- **Transaction Entry Screen:**
[Transaction Entry Screen](screenshots/add_new_transaction.jpg)

- **Push Notification:**
[Push Notification](screenshots/notification.jpg)



## Features

*   **Automatic SMS Parsing:**
    *   Listens for incoming SMS messages.
    *   Uses customizable patterns to extract transaction details like amount, merchant name, and date.
    *   Currently supports patterns for common Indian banks/services (e.g., "debited from", "spent at", "payment of INR", "paid to ... on ...").
*   **Manual Transaction Entry:**
    *   Allows users to manually add transactions that were not captured via SMS or for cash expenses.
*   **Transaction Listing:**
    *   Displays a clear, chronological list of all transactions.
    *   Shows merchant name, amount, category, and date for each transaction.
*   **Transaction Details & Editing:**
    *   Tap on a transaction to view/edit details including:
        *   Amount
        *   Merchant
        *   Category (Food, Travel, Shopping, Bills, etc.)
        *   Notes
        *   Date (auto-filled from SMS, editable)
*   **Categorization:**
    *   Assign categories to transactions for better financial organization.
*   **Data Persistence:**
    *   Uses Room Persistence Library (SQLite) to store transaction data locally on the device.
*   **CSV Export:**
    *   Export all transaction data to a CSV file saved in the public "Downloads" folder for backup or use in other financial software.
*   **Modern UI:**
    *   Built with Material Design 3 components.
    *   Clean and intuitive user interface.
*   **Permission Handling:**
    *   Requests necessary permissions (SMS Read) with clear explanations.


## Tech Stack & Architecture

*   **Language:** Kotlin
*   **Architecture:** MVVM (Model-View-ViewModel) - *Primarily using ViewModel with LiveData for UI updates.*
*   **Core Android Jetpack Components:**
    *   **Room:** For local database storage.
    *   **LiveData:** To observe data changes and update UI reactively.
    *   **ViewModel:** To manage UI-related data and survive configuration changes.
    *   **Navigation Component (Implied for larger apps, currently basic):** For navigating between screens.
    *   **Material Components for Android:** For Material Design 3 UI elements.
*   **Coroutines:** For asynchronous operations like database access and SMS processing.
*   **Permissions Handling:** For requesting SMS permissions at runtime.
*   **BroadcastReceiver:** For listening to incoming SMS messages.
*   **RecyclerView:** For displaying lists of transactions.
*   **Regular Expressions (Regex):** For parsing SMS content.

## Project Structure (Key Components)

*   **`data/`**
    *   `Transaction.kt`: Data class representing a single transaction.
    *   `AppDatabase.kt`: Room database definition.
    *   `TransactionDao.kt`: Data Access Object for transaction operations.
*   **`SmsReceiver.kt`**: `BroadcastReceiver` to intercept and parse SMS messages.
*   **`TransactionListActivity.kt`**: Displays the list of all transactions and handles export.
*   **`TransactionAdapter.kt`**: Adapter for the RecyclerView in `TransactionListActivity`.
*   **`TransactionEntryActivity.kt`**: For manually adding or editing transaction details.
*   **`TransactionViewModel.kt`**: ViewModel to hold and manage transaction data for `TransactionListActivity`.
*   **Layouts (`res/layout/`)**: XML files defining the UI structure (e.g., `activity_transaction_list.xml`, `item_transaction.xml`).
*   **Menus (`res/menu/`)**: XML for options menus (e.g., export icon).

## Setup & Build

1.  **Clone the repository:**
    `git clone https://github.com/HimanshuSharma789/PaymentSmsTracker.git`
3.  **Open in Android Studio:**
    *   Open Android Studio.
    *   Select "Open an Existing Project".
    *   Navigate to the cloned directory and select it.
4.  **Build the project:**
    *   Android Studio should automatically sync Gradle dependencies.
    *   Click the "Build" button (hammer icon) or "Run" (play icon) to build and install on an emulator or connected device.

## Permissions

The app requires the following permissions:

*   **`android.permission.RECEIVE_SMS`**: To read incoming SMS messages for automatic transaction detection.
*   **`android.permission.READ_SMS`**: Potentially needed by the SMS parsing logic if it needs to query existing SMS.
*   **`android.permission.WRITE_EXTERNAL_STORAGE` (for Android 9 and below)**: Required by the CSV export feature to save files to the public Downloads folder. On Android 10+, `MediaStore` is used, which generally doesn't require this permission for app-created files in standard directories.

The app will request these permissions at runtime when they are needed.

## SMS Parsing Logic

The `SmsReceiver.kt` contains regular expressions (`Pattern`) to parse transaction details from SMS messages. These patterns are designed for common formats but may need adjustments or additions to support a wider variety of bank SMS formats.

Key patterns include:
*   Extracting amount (e.g., "Rs. XXX", "INR XXX").
*   Extracting merchant name (e.g., "to MERCHANT on", "at MERCHANT").
*   Extracting date.

## Future Enhancements (Potential Ideas)

*   [ ] More robust SMS parsing for a wider range of bank formats.
*   [ ] User-configurable SMS parsing patterns.
*   [ ] Charts and graphs for visualizing spending.
*   [ ] Budgeting features.
*   [ ] Cloud backup and sync.
*   [ ] Biometric authentication.
*   [ ] Dark Mode theme improvements.
*   [ ] Filtering and searching transactions.
*   [ ] Unit and Instrumented Tests.

## Contributing

Contributions are welcome! If you'd like to contribute, please follow these steps:

1.  Fork the repository.
2.  Create a new branch (`git checkout -b feature/your-feature-name`).
3.  Make your changes.
4.  Commit your changes (`git commit -am 'Add some feature'`).
5.  Push to the branch (`git push origin feature/your-feature-name`).
6.  Create a new Pull Request.

Please ensure your code adheres to Kotlin coding conventions and Material Design guidelines.
