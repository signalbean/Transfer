document.addEventListener('DOMContentLoaded', () => {
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const filesTableBody = document.querySelector('#files-table tbody');
    const uploadProgressContainer = document.getElementById('upload-progress-container');
    const noFilesMessage = document.getElementById('no-files-message');
    const themeToggleButton = document.getElementById('theme-toggle');
    const themeIcon = document.getElementById('theme-icon');

    // Modal elements
    const confirmationModalOverlay = document.getElementById('confirmation-modal-overlay');
    const confirmationModalMessage = document.getElementById('confirmation-modal-message');
    const modalConfirmButton = document.getElementById('modal-confirm-button');
    const modalCancelButton = document.getElementById('modal-cancel-button');
    const doNotAskAgainCheckbox = document.getElementById('do-not-ask-again');

    // --- Theme Toggle ---
    function applyTheme(theme) {
        if (theme === 'dark') {
            document.body.classList.add('dark-mode');
            themeIcon.textContent = '☀️'; // Sun icon for dark mode (to switch to light)
        } else {
            document.body.classList.remove('dark-mode');
            themeIcon.textContent = '🌙'; // Moon icon for light mode (to switch to dark)
        }
    }

    // Load theme preference from localStorage
    const savedTheme = localStorage.getItem('theme');
    if (savedTheme) {
        applyTheme(savedTheme);
    } else {
        // Detect system preference for dark or light mode
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        applyTheme(prefersDark ? 'dark' : 'light');
    }

    // Toggle theme on button click
    themeToggleButton.addEventListener('click', () => {
        const currentTheme = document.body.classList.contains('dark-mode') ? 'dark' : 'light';
        const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
        applyTheme(newTheme);
        localStorage.setItem('theme', newTheme);
    });

    // --- Custom Confirmation Modal Logic ---
    let currentConfirmCallback = null;

    /**
     * Shows a custom confirmation modal.
     * @param {string} message The message to display in the modal.
     * @param {function} onConfirm Callback function to execute if the user confirms.
     */
    function showConfirmModal(message, onConfirm) {
        confirmationModalMessage.textContent = message;
        currentConfirmCallback = onConfirm; // Store the callback
        doNotAskAgainCheckbox.checked = false; // Reset checkbox state every time modal is opened

        confirmationModalOverlay.classList.add('active'); // Show modal

        // Ensure previous listeners are removed to prevent multiple calls
        modalConfirmButton.onclick = null;
        modalCancelButton.onclick = null;

        modalConfirmButton.onclick = () => {
            if (doNotAskAgainCheckbox.checked) {
                localStorage.setItem('doNotAskAgainDelete', 'true'); // Set preference
            }
            if (currentConfirmCallback) {
                currentConfirmCallback(true);
            }
            hideConfirmModal();
        };

        modalCancelButton.onclick = () => {
            if (currentConfirmCallback) {
                currentConfirmCallback(false); // Indicate cancellation
            }
            hideConfirmModal();
        };

        // Allow clicking outside to close
        confirmationModalOverlay.addEventListener('click', (event) => {
            if (event.target === confirmationModalOverlay) {
                if (currentConfirmCallback) {
                    currentConfirmCallback(false); // Indicate cancellation
                }
                hideConfirmModal();
            }
        }, { once: true }); // Use once to prevent multiple bindings
    }

    function hideConfirmModal() {
        confirmationModalOverlay.classList.remove('active');
        currentConfirmCallback = null; // Clear the callback
    }


    // --- File Listing ---
    async function fetchFiles() {
        try {
            const response = await fetch('/api/files');
            if (!response.ok) {
                const errorText = await response.text();
                console.error('Error fetching files:', response.status, errorText);
                filesTableBody.innerHTML = `<tr><td colspan="5" style="color: var(--error-color);">Error loading files: ${errorText}</td></tr>`;
                noFilesMessage.style.display = 'none';
                return;
            }
            const data = await response.json();
            renderFiles(data.files);
        } catch (error) {
            console.error('Failed to fetch files:', error);
            filesTableBody.innerHTML = `<tr><td colspan="5" style="color: var(--error-color);">Could not connect to server or error fetching files.</td></tr>`;
            noFilesMessage.style.display = 'none';
        }
    }

    function renderFiles(files) {
        filesTableBody.innerHTML = ''; // Clear existing files
        if (!files || files.length === 0) {
            noFilesMessage.style.display = 'block';
            return;
        }
        noFilesMessage.style.display = 'none';

        files.forEach(file => {
            const row = filesTableBody.insertRow();
            // Dynamically add data attributes for easy access
            row.dataset.fileName = file.name;

            // Add data-label attributes for responsive CSS
            const nameCell = row.insertCell();
            nameCell.textContent = file.name;
            nameCell.dataset.label = 'Name';

            const sizeCell = row.insertCell();
            sizeCell.textContent = file.formattedSize || formatBytes(file.size);
            sizeCell.dataset.label = 'Size';

            const modifiedCell = row.insertCell();
            modifiedCell.textContent = file.lastModified;
            modifiedCell.dataset.label = 'Last Modified';

            const typeCell = row.insertCell();
            typeCell.textContent = file.type;
            typeCell.dataset.label = 'Type';

            // Add the action icons container to the last cell
            const actionsCell = row.insertCell();
            actionsCell.dataset.label = 'Actions';
            actionsCell.style.textAlign = 'right'; // Align icons to the right

            const actionIconsContainer = document.createElement('div');
            actionIconsContainer.className = 'action-icons-container';

            // Download Icon
            const downloadLink = document.createElement('a');
            downloadLink.href = file.downloadUrl;
            downloadLink.setAttribute('download', file.name); // Suggest filename for download
            downloadLink.className = 'icon-button download-icon';
            downloadLink.title = `Download ${file.name}`;
            downloadLink.innerHTML = `
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                        <path d="M12 16L7 11H11V4H13V11H17L12 16ZM20 18H4V20H20V18Z"/>
                    </svg>
                `;
            actionIconsContainer.appendChild(downloadLink);

            // Delete Icon
            const deleteButton = document.createElement('button');
            deleteButton.className = 'icon-button delete-icon';
            deleteButton.title = `Delete ${file.name}`;
            deleteButton.innerHTML = `
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                        <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12ZM19 4h-3.5l-1-1h-5l-1 1H5V6h14V4Z"/>
                    </svg>
                `;
            deleteButton.onclick = (event) => {
                event.stopPropagation(); // Prevent row click from interfering if any
                confirmDeleteFile(file.name);
            };
            actionIconsContainer.appendChild(deleteButton);

            actionsCell.appendChild(actionIconsContainer);
        });
    }

        function confirmDeleteFile(fileName) {
            // Check localStorage preference first
            const doNotAskAgain = localStorage.getItem('doNotAskAgainDelete') === 'true';

            if (doNotAskAgain) {
                deleteFile(fileName); // Proceed directly if preference is set
            } else {
                showConfirmModal(`Delete "${fileName}"?`, (confirmed) => { // Shorter message
                    if (confirmed) {
                        deleteFile(fileName);
                    }
                });
            }
        }

    async function deleteFile(fileName) {
        try {
            const response = await fetch('/api/delete', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ filename: fileName })
            });
            const result = await response.json();
            if (response.ok) {
                // Update the specific row
                const deletedRow = filesTableBody.querySelector(`[data-file-name="${fileName}"]`);
                if (deletedRow) {
                    deletedRow.remove();
                }
                if (filesTableBody.children.length === 0) {
                    noFilesMessage.style.display = 'block';
                }
                // Optionally show a temporary success message
                console.log(`Successfully deleted: ${fileName}`);
            } else {
                // Using a custom message display instead of alert
                console.error(`Error deleting file: ${result.error || 'Unknown error'}`);
                // A simple inline message could be added or a temporary toast notification
                const errorMsg = document.createElement('p');
                errorMsg.textContent = `Failed to delete ${fileName}: ${result.error || 'Unknown error'}`;
                errorMsg.style.color = 'var(--error-color)';
                errorMsg.style.marginTop = '10px';
                errorMsg.style.textAlign = 'center';
                uploadProgressContainer.appendChild(errorMsg); // Temporary display area
                setTimeout(() => errorMsg.remove(), 5000); // Remove after 5 seconds
            }
        } catch (error) {
            console.error('Failed to send delete request:', error);
            const errorMsg = document.createElement('p');
            errorMsg.textContent = `Failed to send delete request for "${fileName}". Please check network.`;
            errorMsg.style.color = 'var(--error-color)';
            errorMsg.style.marginTop = '10px';
            errorMsg.style.textAlign = 'center';
            uploadProgressContainer.appendChild(errorMsg); // Temporary display area
            setTimeout(() => errorMsg.remove(), 5000); // Remove after 5 seconds
        }
    }


    // --- Drag and Drop & File Upload ---
    dropZone.addEventListener('click', () => fileInput.click());

    dropZone.addEventListener('dragover', (event) => {
        event.preventDefault();
        dropZone.classList.add('dragover');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('dragover');
    });

    dropZone.addEventListener('drop', (event) => {
        event.preventDefault();
        dropZone.classList.remove('dragover');
        const files = event.dataTransfer.files;
        if (files.length > 0) {
            handleFiles(files);
        }
    });

    fileInput.addEventListener('change', (event) => {
        const files = event.target.files;
        if (files.length > 0) {
            handleFiles(files);
        }
        // Clear the file input so the same file can be selected again
        event.target.value = '';
    });

    function handleFiles(files) {
        Array.from(files).forEach(file => {
            uploadFile(file);
        });
    }

    function uploadFile(file) {
        const formData = new FormData();
        formData.append('file', file, file.name);

        const xhr = new XMLHttpRequest();

        // Create progress display for this file
        const progressItem = document.createElement('div');
        progressItem.className = 'progress-bar-item';
        const fileNameSpan = document.createElement('span');
        fileNameSpan.textContent = `${file.name} (${formatBytes(file.size)}): `;
        const progressBar = document.createElement('div');
        progressBar.className = 'progress-bar';
        const progressBarFill = document.createElement('div');
        progressBarFill.className = 'progress-bar-fill';
        const progressStatus = document.createElement('span');
        progressStatus.className = 'progress-bar-status';

        progressBar.appendChild(progressBarFill);
        progressItem.appendChild(fileNameSpan);
        progressItem.appendChild(progressBar);
        progressItem.appendChild(progressStatus);
        uploadProgressContainer.prepend(progressItem); // Add new progress at the top


        xhr.upload.addEventListener('progress', (event) => {
            if (event.lengthComputable) {
                const percentComplete = (event.loaded / event.total) * 100;
                progressBarFill.style.width = percentComplete.toFixed(2) + '%';
                progressStatus.textContent = `${percentComplete.toFixed(0)}%`;
            }
        });

        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                progressBarFill.style.backgroundColor = 'var(--success-color)';
                progressStatus.textContent = `Success: ${xhr.responseText}`;
                setTimeout(() => progressItem.remove(), 3000); // Remove success item after 3 seconds
                fetchFiles(); // Refresh file list after successful upload
            } else {
                progressBarFill.style.backgroundColor = 'var(--error-color)';
                progressStatus.textContent = `Error: ${xhr.status} - ${xhr.responseText || 'Upload failed'}`;
                console.error('Upload failed:', xhr.status, xhr.responseText);
                // Keep error message visible or provide a clear indication
            }
        });

        xhr.addEventListener('error', () => {
            progressBarFill.style.backgroundColor = 'var(--error-color)';
            progressStatus.textContent = 'Network Error';
            console.error('Upload error (network).');
        });

        xhr.open('POST', '/api/upload', true);
        xhr.send(formData);
    }

    /**
     * Formats bytes into a human-readable string (e.g., 1.23 MB).
     * @param {number} bytes The number of bytes.
     * @param {number} decimals The number of decimal places for the output.
     * @returns {string} Formatted size string.
     * This is, of course, GPT. If you said you have more than 1TB, I won't believe you, and YB is a trillion TB. :)
     */
    function formatBytes(bytes, decimals = 2) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }

    // Initial load of files when the page is ready
    fetchFiles();
});