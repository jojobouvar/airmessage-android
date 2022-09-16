package me.tagavari.airmessage.compose.state

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import me.tagavari.airmessage.connection.ConnectionManager
import me.tagavari.airmessage.connection.exception.AMRequestException
import me.tagavari.airmessage.data.DatabaseManager
import me.tagavari.airmessage.enums.*
import me.tagavari.airmessage.helper.AddressHelper
import me.tagavari.airmessage.helper.ConversationColorHelper.getColoredMembers
import me.tagavari.airmessage.helper.ConversationColorHelper.getDefaultConversationColor
import me.tagavari.airmessage.messaging.ConversationInfo
import me.tagavari.airmessage.messaging.ConversationPreview.ChatCreation
import me.tagavari.airmessage.redux.ReduxEmitterNetwork.messageUpdateSubject
import me.tagavari.airmessage.redux.ReduxEventMessaging.ConversationUpdate
import me.tagavari.airmessage.task.ContactsTask
import me.tagavari.airmessage.util.ContactInfo

@OptIn(SavedStateHandleSaveableApi::class)
class NewConversationViewModel(
	application: Application,
	savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
	//Contacts list
	var contactsState by mutableStateOf<NewConversationContactsState>(NewConversationContactsState.Loading)
		private set
	
	//Selected service
	var selectedService by savedStateHandle.saveable { mutableStateOf(MessageServiceDescription.IMESSAGE) }
	
	//Selected recipients
	var selectedRecipients by savedStateHandle.saveable { mutableStateOf(LinkedHashSet<SelectedRecipient>()) }
		private set
	
	//Loading state
	var isLoading by mutableStateOf(false)
		private set
	
	//Assigned when a conversation is ready to be launched
	val launchFlow = MutableStateFlow<ConversationInfo?>(null)
	
	init {
		loadContacts()
	}
	
	fun loadContacts() {
		//Check if we need permission
		if(ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
			contactsState = NewConversationContactsState.NeedsPermission
			return
		}
		
		//Set the state to loading
		contactsState = NewConversationContactsState.Loading
		
		viewModelScope.launch {
			val contacts = try {
				//Load contacts
				ContactsTask.loadContacts(getApplication()).asFlow()
					.fold(mutableListOf<ContactInfo>()) { list, contactPart ->
						//Fold multiple contact parts into a single contact
						val matchingContact = list.firstOrNull { it.contactID == contactPart.id }
						if(matchingContact != null) {
							matchingContact.addresses.add(contactPart.address)
						} else {
							list.add(
								ContactInfo(
									contactPart.id,
									contactPart.name,
									contactPart.thumbnailURI,
									mutableListOf(contactPart.address)
								)
							)
						}
						
						list
					}
			} catch(exception: Throwable) {
				contactsState = NewConversationContactsState.Error(exception)
				return@launch
			}
			
			contactsState = NewConversationContactsState.Loaded(contacts)
		}
	}
	
	/**
	 * Adds a selected recipient to the list
	 */
	fun addSelectedRecipient(recipient: SelectedRecipient) {
		selectedRecipients = LinkedHashSet(selectedRecipients).also { collection ->
			collection.add(recipient)
		}
	}
	
	/**
	 * Removes a selected recipient from the list
	 */
	fun removeSelectedRecipient(recipient: SelectedRecipient) {
		selectedRecipients = LinkedHashSet(selectedRecipients).also { collection ->
			collection.remove(recipient)
		}
	}
	
	fun createConversation(connectionManager: ConnectionManager?) {
		viewModelScope.launch {
			isLoading = true
			
			val serviceHandler = selectedService.serviceHandler
			val serviceType = selectedService.serviceType
			val recipients = selectedRecipients.map { AddressHelper.normalizeAddress(it.address) }
			
			try {
				val (conversation, isConversationNew) = if(serviceHandler == ServiceHandler.appleBridge) {
					//Try to create the chat on the server
					try {
						//Fail immediately if we have no network connection
						if(connectionManager == null) {
							throw AMRequestException(ChatCreateErrorCode.network)
						}
						
						//Request the creation of the chat
						val chatGUID = connectionManager.createChat(recipients.toTypedArray(), serviceType).await()
						
						//Try to find a matching conversation in the database, or create a new one
						withContext(Dispatchers.IO) {
							DatabaseManager.getInstance().addRetrieveMixedConversationInfoAMBridge(getApplication(), chatGUID, recipients, serviceType)
						}
					} catch(exception: Exception) {
						//Create an unlinked conversation locally
						withContext(Dispatchers.IO) {
							DatabaseManager.getInstance().addRetrieveClientCreatedConversationInfo(
								getApplication(),
								recipients,
								ServiceHandler.appleBridge,
								selectedService.serviceType
							)
						}
					}
				} else if(serviceHandler == ServiceHandler.systemMessaging) {
					if(serviceType == ServiceType.systemSMS) {
						withContext(Dispatchers.IO) {
							//Find or create a matching conversation in Android's message database
							val threadID = Telephony.Threads.getOrCreateThreadId(
								getApplication(),
								recipients.toSet()
							)
							
							//Find a matching conversation in AirMessage's database
							DatabaseManager.getInstance()
								.findConversationByExternalID(
									getApplication(),
									threadID,
									serviceHandler,
									serviceType
								)
								?.let { Pair(it, false) }
								?: run {
									//Create the conversation
									val conversationColor = getDefaultConversationColor()
									val coloredMembers = getColoredMembers(recipients, conversationColor)
									val conversationInfo = ConversationInfo(
										-1,
										null,
										threadID,
										ConversationState.ready,
										ServiceHandler.systemMessaging,
										ServiceType.systemSMS,
										conversationColor,
										coloredMembers,
										null
									)
									
									//Write the conversation to disk
									DatabaseManager.getInstance().addConversationInfo(conversationInfo)
									val chatCreateAction = DatabaseManager.getInstance().addConversationCreatedMessage(conversationInfo.localID)
									
									//Set the conversation preview
									conversationInfo.messagePreview = ChatCreation(chatCreateAction.date)
									
									Pair(conversationInfo, true)
								}
						}
					} else {
						throw IllegalStateException("Unsupported service type $serviceType")
					}
				} else {
					throw IllegalStateException("Unsupported service handler $serviceHandler")
				}
				
				//Notify listeners of the new conversation
				if(isConversationNew) {
					messageUpdateSubject.onNext(ConversationUpdate(mapOf(conversation to listOf()), listOf()))
				}
				
				//Launch the conversation
				launchFlow.emit(conversation)
			} finally {
				//Reset the state
				isLoading = false
				selectedService = MessageServiceDescription.IMESSAGE
				selectedRecipients = LinkedHashSet()
			}
		}
	}
}

data class SelectedRecipient(
	val address: String,
	val name: String? = null
) {
	val displayLabel: String
		get() = name ?: address
	
	override fun equals(other: Any?): Boolean {
		if(other !is SelectedRecipient) return false
		return address == other.address
	}
	
	override fun hashCode() = address.hashCode()
}

sealed class NewConversationContactsState {
	object Loading : NewConversationContactsState()
	object NeedsPermission : NewConversationContactsState()
	class Error(val exception: Throwable) : NewConversationContactsState()
	class Loaded(val contacts: List<ContactInfo>) : NewConversationContactsState()
}