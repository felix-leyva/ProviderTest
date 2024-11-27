package de.ams.providertest

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import de.ams.providertest.ui.theme.ProviderTestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream


class MainActivity : ComponentActivity() {

    private fun shareFile() = lifecycleScope.launch {
        // Create a file to share
        val file = File(cacheDir, "shared_file.txt")
        if (file.exists()) {
            file.delete()
        }
        val encryptedFileWrapper =
            EncryptedFileWrapper(context = this@MainActivity, targetFile = file)
        
        withContext(Dispatchers.IO) {
            encryptedFileWrapper.getWriteParcelFileDescriptor().use { parcelFileDescriptor ->
                val fileOutputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)
                fileOutputStream.use { outputStream ->
                    val veryLargeText = (1..100_000).joinToString("\n") { "Hello, World! $it" }
                    outputStream.write(veryLargeText.toByteArray())
                }
            }
        }
        
        // Get the URI for the file
        val uri = Provider.getUri(context = this@MainActivity, file = file)

        // Share the file
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProviderTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column {
                        Greeting(
                            name = "Android",
                            modifier = Modifier.padding(innerPadding)
                        )
                        Button(onClick = { shareFile() }) {
                            Text("Share file")
                        }
                    }

                }
            }
        }
    }

}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ProviderTestTheme {
        Greeting("Android")
    }
}
